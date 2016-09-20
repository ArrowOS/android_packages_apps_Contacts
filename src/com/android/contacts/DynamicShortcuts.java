/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.contacts;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.contacts.common.ContactPhotoManager;
import com.android.contacts.common.Experiments;
import com.android.contacts.common.util.BitmapUtil;
import com.android.contacts.common.util.ImplicitIntentsUtil;
import com.android.contactsbind.experiments.Flags;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.android.contacts.common.list.ShortcutIntentBuilder.INTENT_EXTRA_IGNORE_LAUNCH_ANIMATION;

/**
 * This class creates and updates the dynamic shortcuts displayed on the Nexus launcher for the
 * Contacts app.
 *
 * Currently it adds shortcuts for the top 3 contacts in the {@link Contacts#CONTENT_STREQUENT_URI}
 *
 * Usage: DynamicShortcuts.initialize should be called during Application creation. This will
 * schedule a Job to keep the shortcuts up-to-date so no further interations should be necessary.
 */
@TargetApi(Build.VERSION_CODES.N_MR1)
public class DynamicShortcuts {
    private static final String TAG = "DynamicShortcuts";

    // Note the Nexus launcher automatically truncates shortcut labels if they exceed these limits
    // however, we implement our own truncation in case the shortcut is shown on a launcher that
    // has different behavior
    private static final int SHORT_LABEL_MAX_LENGTH = 12;
    private static final int LONG_LABEL_MAX_LENGTH = 30;
    private static final int MAX_SHORTCUTS = 3;

    /**
     * How long  to wait after a change to the contacts content uri before updating the shortcuts
     * This increases the likelihood that multiple updates will be coalesced in the case that
     * the updates are happening rapidly
     *
     * TODO: this should probably be externally configurable to make it easier to manually test the
     * behavior
     */
    private static final int CONTENT_CHANGE_MIN_UPDATE_DELAY_MILLIS = 10000; // 10 seconds
    /**
     * The maximum time to wait before updating the shortcuts that may have changed.
     *
     * TODO: this should probably be externally configurable to make it easier to manually test the
     * behavior
     */
    private static final int CONTENT_CHANGE_MAX_UPDATE_DELAY_MILLIS = 24*60*60*1000; // 1 day

    // The spec specifies that it should be 44dp @ xxxhdpi
    // Note that ShortcutManager.getIconMaxWidth and ShortcutManager.getMaxHeight return different
    // (larger) values.
    private static final int RECOMMENDED_ICON_PIXEL_LENGTH = 176;

    @VisibleForTesting
    static final String[] PROJECTION = new String[] {
            Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY
    };

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ShortcutManager mShortcutManager;
    private int mShortLabelMaxLength = SHORT_LABEL_MAX_LENGTH;
    private int mLongLabelMaxLength = LONG_LABEL_MAX_LENGTH;

    public DynamicShortcuts(Context context) {
        this(context, context.getContentResolver(), (ShortcutManager)
                context.getSystemService(Context.SHORTCUT_SERVICE));
    }

    public DynamicShortcuts(Context context, ContentResolver contentResolver,
            ShortcutManager shortcutManager) {
        mContext = context;
        mContentResolver = contentResolver;
        mShortcutManager = shortcutManager;
    }

    @VisibleForTesting
    void setShortLabelMaxLength(int length) {
        this.mShortLabelMaxLength = length;
    }

    @VisibleForTesting
    void setLongLabelMaxLength(int length) {
        this.mLongLabelMaxLength = length;
    }

    @VisibleForTesting
    void refresh() {
        mShortcutManager.setDynamicShortcuts(getStrequentShortcuts());
        updatePinned();
    }

    @VisibleForTesting
    void updatePinned() {
        final List<ShortcutInfo> updates = new ArrayList<>();
        final List<String> removedIds = new ArrayList<>();
        final List<String> enable = new ArrayList<>();

        for (ShortcutInfo shortcut : mShortcutManager.getPinnedShortcuts()) {

            final PersistableBundle extras = shortcut.getExtras();
            // The contact ID may have changed but that's OK because it is just an optimization
            final long contactId = extras == null ? 0 : extras.getLong(Contacts._ID);

            final ShortcutInfo update = createShortcutForUri(
                    Contacts.getLookupUri(contactId, shortcut.getId()));
            if (update != null) {
                updates.add(update);
                if (!shortcut.isEnabled()) {
                    // Handle the case that a contact is disabled because it doesn't exist but
                    // later is created (for instance by a sync)
                    enable.add(update.getId());
                }
            } else if (shortcut.isEnabled()) {
                removedIds.add(shortcut.getId());
            }
        }

        mShortcutManager.updateShortcuts(updates);
        mShortcutManager.enableShortcuts(enable);
        mShortcutManager.disableShortcuts(removedIds,
                mContext.getString(R.string.dynamic_shortcut_contact_removed_message));
    }

    private ShortcutInfo createShortcutForUri(Uri contactUri) {
        final Cursor cursor = mContentResolver.query(contactUri, PROJECTION, null, null, null);
        if (cursor == null) return null;

        try {
            if (cursor.moveToFirst()) {
                return createShortcutFromRow(cursor);
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    public List<ShortcutInfo> getStrequentShortcuts() {
        // The limit query parameter doesn't seem to work for this uri but we'll leave it because in
        // case it does work on some phones or platform versions.
        final Uri uri = Contacts.CONTENT_STREQUENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY,
                        String.valueOf(MAX_SHORTCUTS))
                .build();
        final Cursor cursor = mContentResolver.query(uri, PROJECTION, null, null, null);

        if (cursor == null) return Collections.emptyList();

        final List<ShortcutInfo> result = new ArrayList<>();

        try {
            // For some reason the limit query parameter is ignored for the strequent content uri
            for (int i = 0; i < MAX_SHORTCUTS && cursor.moveToNext(); i++) {
                result.add(createShortcutFromRow(cursor));
            }
        } finally {
            cursor.close();
        }
        return result;
    }


    @VisibleForTesting
    ShortcutInfo createShortcutFromRow(Cursor cursor) {
        final ShortcutInfo.Builder builder = builderForContactShortcut(cursor);
        addIconForContact(cursor, builder);
        return builder.build();
    }

    @VisibleForTesting
    ShortcutInfo.Builder builderForContactShortcut(Cursor cursor) {
        final long id = cursor.getLong(0);
        final String lookupKey = cursor.getString(1);
        final String displayName = cursor.getString(2);
        return builderForContactShortcut(id, lookupKey, displayName);
    }

    @VisibleForTesting
    ShortcutInfo.Builder builderForContactShortcut(long id, String lookupKey, String displayName) {
        final PersistableBundle extras = new PersistableBundle();
        extras.putLong(Contacts._ID, id);

        final ShortcutInfo.Builder builder = new ShortcutInfo.Builder(mContext, lookupKey)
                .setIntent(ImplicitIntentsUtil.getIntentForQuickContactLauncherShortcut(mContext,
                        Contacts.getLookupUri(id, lookupKey)))
                .setDisabledMessage(mContext.getString(R.string.dynamic_shortcut_disabled_message))
                .setExtras(extras);

        if (displayName.length() < mLongLabelMaxLength) {
            builder.setLongLabel(displayName);
        } else {
            builder.setLongLabel(displayName.substring(0, mLongLabelMaxLength - 1).trim() + "…");
        }

        if (displayName.length() < mShortLabelMaxLength) {
            builder.setShortLabel(displayName);
        } else {
            builder.setShortLabel(displayName.substring(0, mShortLabelMaxLength - 1).trim() + "…");
        }
        return builder;
    }

    private void addIconForContact(Cursor cursor, ShortcutInfo.Builder builder) {
        final long id = cursor.getLong(0);
        final String lookupKey = cursor.getString(1);
        final String displayName = cursor.getString(2);

        final Bitmap bitmap = getContactPhoto(id);
        if (bitmap != null) {
            builder.setIcon(Icon.createWithBitmap(bitmap));
        } else {
            builder.setIcon(Icon.createWithBitmap(getFallbackAvatar(displayName, lookupKey)));
        }
    }

    private Bitmap getContactPhoto(long id) {
        final InputStream photoStream = Contacts.openContactPhotoInputStream(
                mContext.getContentResolver(),
                ContentUris.withAppendedId(Contacts.CONTENT_URI, id), true);

        if (photoStream == null) return null;
        try {
            final Bitmap bitmap = decodeStreamForShortcut(photoStream);
            photoStream.close();
            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "Failed to decode contact photo for shortcut. ID=" + id, e);
            return null;
        } finally {
            try {
                photoStream.close();
            } catch (IOException e) {
                // swallow
            }
        }
    }

    private Bitmap decodeStreamForShortcut(InputStream stream) throws IOException {
        final BitmapRegionDecoder bitmapDecoder = BitmapRegionDecoder.newInstance(stream, false);

        final int sourceWidth = bitmapDecoder.getWidth();
        final int sourceHeight = bitmapDecoder.getHeight();

        final int iconMaxWidth = mShortcutManager.getIconMaxWidth();;
        final int iconMaxHeight = mShortcutManager.getIconMaxHeight();

        final int sampleSize = Math.min(
                BitmapUtil.findOptimalSampleSize(sourceWidth,
                        RECOMMENDED_ICON_PIXEL_LENGTH),
                BitmapUtil.findOptimalSampleSize(sourceHeight,
                        RECOMMENDED_ICON_PIXEL_LENGTH));
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;

        final int scaledWidth = sourceWidth / opts.inSampleSize;
        final int scaledHeight = sourceHeight / opts.inSampleSize;

        final int targetWidth = Math.min(scaledWidth, iconMaxWidth);
        final int targetHeight = Math.min(scaledHeight, iconMaxHeight);

        // Make it square.
        final int targetSize = Math.min(targetWidth, targetHeight);

        // The region is defined in the coordinates of the source image then the sampling is
        // done on the extracted region.
        final int prescaledXOffset = ((scaledWidth - targetSize) * opts.inSampleSize) / 2;
        final int prescaledYOffset = ((scaledHeight - targetSize) * opts.inSampleSize) / 2;

        final Bitmap bitmap = bitmapDecoder.decodeRegion(new Rect(
                prescaledXOffset, prescaledYOffset,
                sourceWidth - prescaledXOffset, sourceHeight - prescaledYOffset
        ), opts);

        bitmapDecoder.recycle();

        return BitmapUtil.getRoundedBitmap(bitmap, targetSize, targetSize);
    }

    private Bitmap getFallbackAvatar(String displayName, String lookupKey) {
        final int w = RECOMMENDED_ICON_PIXEL_LENGTH;
        final int h = RECOMMENDED_ICON_PIXEL_LENGTH;

        final ContactPhotoManager.DefaultImageRequest request =
                new ContactPhotoManager.DefaultImageRequest(displayName, lookupKey, true);
        final Drawable avatar = ContactPhotoManager.getDefaultAvatarDrawableForContact(
                mContext.getResources(), true, request);
        final Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        // The avatar won't draw unless it thinks it is visible
        avatar.setVisible(true, true);
        final Canvas canvas = new Canvas(result);
        avatar.setBounds(0, 0, w, h);
        avatar.draw(canvas);
        return result;
    }

    private void handleFlagDisabled() {
        mShortcutManager.removeAllDynamicShortcuts();

        final List<ShortcutInfo> pinned = mShortcutManager.getPinnedShortcuts();
        final List<String> ids = new ArrayList<>(pinned.size());
        for (ShortcutInfo shortcut : pinned) {
            ids.add(shortcut.getId());
        }
        mShortcutManager.disableShortcuts(ids, mContext
                .getString(R.string.dynamic_shortcut_disabled_message));
    }

    @VisibleForTesting
    void scheduleUpdateJob() {
        final JobInfo job = new JobInfo.Builder(
                ContactsJobService.DYNAMIC_SHORTCUTS_JOB_ID,
                new ComponentName(mContext, ContactsJobService.class))
                // We just observe all changes to contacts. It would be better to be more granular
                // but CP2 only notifies using this URI anyway so there isn't any point in adding
                // that complexity.
                .addTriggerContentUri(new JobInfo.TriggerContentUri(ContactsContract.AUTHORITY_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(CONTENT_CHANGE_MIN_UPDATE_DELAY_MILLIS)
                .setTriggerContentMaxDelay(CONTENT_CHANGE_MAX_UPDATE_DELAY_MILLIS).build();
        final JobScheduler scheduler = (JobScheduler)
                mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(job);
    }

    public synchronized static void initialize(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;

        final DynamicShortcuts shortcuts = new DynamicShortcuts(context);
        if (!Flags.getInstance(context).getBoolean(Experiments.DYNAMIC_SHORTCUTS)) {
            // Clear dynamic shortcuts if the flag is not enabled. This prevents shortcuts from
            // staying around if it is enabled then later disabled (due to bugs for instance).
            shortcuts.handleFlagDisabled();
        } else if (!isJobScheduled(context)) {
            // Update the shortcuts. If the job is already scheduled then either the app is being
            // launched to run the job in which case the shortcuts will get updated when it runs or
            // it has been launched for some other reason and the data we care about for shortcuts
            // hasn't changed. Because the job reschedules itself after completion this check
            // essentially means that this will run on each app launch that happens after a reboot.
            // Note: the task schedules the job after completing.
            new ShortcutUpdateTask(shortcuts).execute();
        }
    }

    public static void updateFromJob(final JobService service, final JobParameters jobParams) {
        new ShortcutUpdateTask(new DynamicShortcuts(service)) {
            @Override
            protected void onPostExecute(Void aVoid) {
                // Must call super first which will reschedule the job before we call jobFinished
                super.onPostExecute(aVoid);
                service.jobFinished(jobParams, false);
            }
        }.execute();
    }

    @VisibleForTesting
    public static boolean isJobScheduled(Context context) {
        final JobScheduler scheduler = (JobScheduler) context
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler.getPendingJob(ContactsJobService.DYNAMIC_SHORTCUTS_JOB_ID) != null;
    }

    private static class ShortcutUpdateTask extends AsyncTask<Void, Void, Void> {
        private DynamicShortcuts mDynamicShortcuts;

        public ShortcutUpdateTask(DynamicShortcuts shortcuts) {
            mDynamicShortcuts = shortcuts;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            mDynamicShortcuts.refresh();
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            // The shortcuts may have changed so update the job so that we are observing the
            // correct Uris
            mDynamicShortcuts.scheduleUpdateJob();
        }
    }
}