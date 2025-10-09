package com.styly.deviceidprovider;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class SafHelper {
    private static final String PREF = "com.styly.deviceidprovider.prefs";
    private static final String KEY_TREE_URI = "treeUri";
    static final String FILE_NAME = "device-id-provider.json";

    private SafHelper() {}

    static void getOrCreateDeviceId(@NonNull Activity activity, @NonNull DeviceIdProvider.Callback cb) {
        Uri tree = getPersistedTree(activity);
        if (tree == null) {
            // Launch transparent activity to request a tree; callback when done
            IdProviderUiActivity.requestTree(activity, new IdProviderUiActivity.Result() {
                @Override public void onTreeReady(@NonNull Uri treeUri) {
                    persistTree(activity, treeUri);
                    tryResolveInTree(activity, treeUri, cb);
                }
                @Override public void onError(@NonNull String code, @NonNull String message) {
                    cb.onError(code, message);
                }
            });
            return;
        }
        tryResolveInTree(activity, tree, cb);
    }

    @Nullable
    static String peekDeviceId(@NonNull Context context) {
        Uri tree = getPersistedTree(context);
        if (tree == null) return null;
        DocumentFile dir = DocumentFile.fromTreeUri(context, tree);
        if (dir == null) return null;
        DocumentFile file = dir.findFile(FILE_NAME);
        if (file == null || !file.isFile()) return null;
        try {
            return readGuid(context.getContentResolver(), file.getUri());
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void tryResolveInTree(@NonNull Activity activity, @NonNull Uri tree, @NonNull DeviceIdProvider.Callback cb) {
        DocumentFile dir = DocumentFile.fromTreeUri(activity, tree);
        if (dir == null) {
            cb.onError("E_SAF", "Invalid tree uri");
            return;
        }
        DocumentFile file = dir.findFile(FILE_NAME);
        final ContentResolver resolver = activity.getContentResolver();
        try {
            if (file != null && file.isFile()) {
                String id = readGuid(resolver, file.getUri());
                if (!TextUtils.isEmpty(id)) {
                    cb.onSuccess(id);
                    return;
                }
                // corrupted: fall through to regenerate
            }
            String newId = UUID.randomUUID().toString().toLowerCase();
            if (file == null) {
                file = dir.createFile("application/json", FILE_NAME);
                if (file == null) {
                    cb.onError("E_SAF", "Failed to create file");
                    return;
                }
            }
            writeJson(resolver, file.getUri(), jsonOf(newId));
            cb.onSuccess(newId);
        } catch (SecurityException se) {
            cb.onError("E_PERMISSION", se.getMessage() != null ? se.getMessage() : "SAF permission required");
        } catch (Throwable t) {
            cb.onError("E_IO", t.getMessage() != null ? t.getMessage() : "I/O error");
        }
    }

    private static void persistTree(@NonNull Activity activity, @NonNull Uri treeUri) {
        if (Build.VERSION.SDK_INT >= 19) {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            try { activity.getContentResolver().takePersistableUriPermission(treeUri, flags); } catch (Throwable ignore) {}
        }
        SharedPreferences sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TREE_URI, treeUri.toString()).apply();
    }

    @Nullable
    private static Uri getPersistedTree(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_TREE_URI, null);
        return s != null ? Uri.parse(s) : null;
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

