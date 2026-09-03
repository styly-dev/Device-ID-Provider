package com.styly.deviceid;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

final class MediaAccess {
    private MediaAccess() {
    }

    /**
     * Returns whether an empty MediaStore query is authoritative for the shared image collection.
     *
     * <p>Without broad read access Android can return only media owned by the caller. Treating that
     * limited empty result as proof that no ID exists would let a second app mint a conflicting
     * identity.</p>
     */
    static boolean canReadAllImages(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager()) {
            return true;
        }

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
