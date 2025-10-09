package com.styly.deviceidprovider;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MediaStoreHelper {
    static final String RELATIVE_PATH = "Download/Device-ID-Provider/";
    static final String DISPLAY_NAME = "device-id-provider.json";
    static final String MIME = "application/json";

    private MediaStoreHelper() {}

    static void getOrCreateDeviceId(@NonNull Activity activity, @NonNull DeviceIdProvider.Callback cb) {
        PermissionHelper.ensureReadExternal(activity, () -> {
            try {
                String id = peekDeviceId(activity);
                if (!TextUtils.isEmpty(id)) {
                    cb.onSuccess(id);
                    return;
                }
                // Create new
                String newId = UUID.randomUUID().toString().toLowerCase();
                Uri uri = insertJson(activity.getContentResolver());
                writeJson(activity.getContentResolver(), uri, jsonOf(newId));
                completePendingIfNeeded(activity.getContentResolver(), uri);
                cb.onSuccess(newId);
            } catch (SecurityException se) {
                cb.onError("E_PERMISSION", se.getMessage() != null ? se.getMessage() : "Permission required");
            } catch (Throwable t) {
                cb.onError("E_IO", t.getMessage() != null ? t.getMessage() : "I/O error");
            }
        }, cb);
    }

    @Nullable
    static String peekDeviceId(@NonNull Context context) {
        try {
            Uri existing = findSingle(context.getContentResolver());
            if (existing == null) return null;
            String id = readGuid(context.getContentResolver(), existing);
            if (TextUtils.isEmpty(id)) return null;
            return id;
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    private static Uri findSingle(@NonNull ContentResolver resolver) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] args = new String[]{ RELATIVE_PATH, DISPLAY_NAME };
        String[] projection = new String[]{ MediaStore.MediaColumns._ID };
        try (android.database.Cursor c = resolver.query(collection, projection, selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return Uri.withAppendedPath(collection, String.valueOf(id));
            }
        }
        return null;
    }

    @NonNull
    private static Uri insertJson(@NonNull ContentResolver resolver) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 30) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        return resolver.insert(collection, values);
    }

    private static void completePendingIfNeeded(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 30) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }

    @Nullable
    private static String readGuid(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString();
            return Json.parseGuid(json);
        }
    }

    private static void writeJson(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull String json) throws IOException {
        try (OutputStream os = resolver.openOutputStream(uri, "w")) {
            if (os == null) throw new IOException("openOutputStream returned null");
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String jsonOf(@NonNull String id) {
        return "{\"device-id\":\"" + id + "\"}\n";
    }
}
