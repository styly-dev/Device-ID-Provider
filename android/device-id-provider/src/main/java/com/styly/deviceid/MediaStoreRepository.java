package com.styly.deviceid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class MediaStoreRepository {
    private static final String TAG = "StylyDeviceId";
    private static final String IMAGES_RELATIVE_PATH = "Pictures/Device-ID-Provider/";
    private static final String PNG_MIME_TYPE = "image/png";

    private final ContentResolver resolver;
    private final Uri imagesUri;

    MediaStoreRepository(ContentResolver resolver) {
        this.resolver = resolver;
        this.imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    CandidateSelector.Selection findWinner() {
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
        };
        String selection = MediaStore.Images.Media.RELATIVE_PATH
                + " LIKE ? AND "
                + MediaStore.Images.Media.DISPLAY_NAME
                + " LIKE ? AND "
                + MediaStore.Images.Media.IS_PENDING
                + " = 0";
        String[] selectionArgs = {IMAGES_RELATIVE_PATH + "%", "%.png"};
        String sortOrder = MediaStore.Images.Media.DATE_ADDED
                + " ASC, "
                + MediaStore.Images.Media._ID
                + " ASC";

        List<MediaCandidate> candidates = new ArrayList<>();
        try (Cursor cursor = resolver.query(
                imagesUri,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {
            if (cursor == null) {
                throw new IllegalStateException("MediaStore query returned a null cursor.");
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (cursor.moveToNext()) {
                candidates.add(new MediaCandidate(
                        cursor.getLong(idColumn),
                        cursor.getLong(dateAddedColumn),
                        cursor.getString(nameColumn)));
            }
        }
        return CandidateSelector.select(candidates);
    }

    Uri createPng(String deviceId) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, deviceId + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME_TYPE);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, IMAGES_RELATIVE_PATH);

        boolean needsPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R;
        if (needsPending) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri createdUri = null;
        try {
            createdUri = resolver.insert(imagesUri, values);
            if (createdUri == null) {
                throw new IOException("ContentResolver.insert returned null.");
            }

            try (OutputStream stream = resolver.openOutputStream(createdUri)) {
                if (stream == null) {
                    throw new IOException("ContentResolver.openOutputStream returned null.");
                }
                stream.write(Png1x1.BYTES);
                stream.flush();
            }

            if (needsPending) {
                ContentValues published = new ContentValues();
                published.put(MediaStore.Images.Media.IS_PENDING, 0);
                int updated = resolver.update(createdUri, published, null, null);
                if (updated <= 0) {
                    Log.w(TAG, "Failed to clear IS_PENDING on created image");
                }
            }
            return createdUri;
        } catch (IOException | RuntimeException error) {
            if (createdUri != null) {
                try {
                    resolver.delete(createdUri, null, null);
                } catch (RuntimeException cleanupError) {
                    Log.w(TAG, "Failed to remove a partial device ID image.", cleanupError);
                }
            }
            throw error;
        }
    }
}
