package com.styly.deviceidprovider.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// No direct dependency on SafHelper; Plus (SAF) path may be disabled.

/** Transparent activity to run SAF flows and return results to static callback. */
public class IdProviderUiActivity extends Activity {
    public interface Result {
        void onTreeReady(@NonNull Uri treeUri);
        void onError(@NonNull String code, @NonNull String message);
    }

    private static final int REQ_OPEN_TREE = 1001;
    private static final int REQ_PERM = 1002;
    private static Result pendingResult;
    private static PermResult pendingPerm;

    public static void requestTree(@NonNull Activity parent, @NonNull Result result) {
        pendingResult = result;
        Intent i = new Intent(parent, IdProviderUiActivity.class);
        parent.startActivity(i);
    }

    public interface PermResult { void onResult(boolean granted); }
    public static void requestReadPermission(@NonNull Activity parent, @NonNull PermResult result) {
        pendingPerm = result;
        Intent i = new Intent(parent, IdProviderUiActivity.class);
        i.putExtra("mode", "perm");
        parent.startActivity(i);
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent() != null ? getIntent().getStringExtra("mode") : null;
        if ("perm".equals(mode)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            // Immediately launch SAF tree picker
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_OPEN_TREE);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE) {
            Result cb = pendingResult;
            pendingResult = null;
            if (cb == null) { finish(); return; }
            if (resultCode == RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    cb.onTreeReady(treeUri);
                    finish();
                    return;
                }
                cb.onError("E_SAF", "No tree uri");
            } else {
                cb.onError("E_CANCEL", "User cancelled");
            }
            finish();
        } else if (requestCode == REQ_PERM) {
            // Should not arrive here; permission handled in onRequestPermissionsResult
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            PermResult r = pendingPerm;
            pendingPerm = null;
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (r != null) r.onResult(granted);
            finish();
        }
    }
}
