package com.styly.deviceidprovider;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.styly.deviceidprovider.ui.IdProviderUiActivity;

final class PermissionHelper {
    private PermissionHelper() {}

    static boolean hasReadExternal(@NonNull Context context) {
        if (Build.VERSION.SDK_INT > 32) return true; // Not applicable
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static void ensureReadExternal(@NonNull Activity activity, @NonNull Runnable onGranted, @NonNull DeviceIdProvider.Callback onDenied) {
        if (hasReadExternal(activity)) {
            onGranted.run();
            return;
        }
        IdProviderUiActivity.requestReadPermission(activity, granted -> {
            if (granted) onGranted.run();
            else onDenied.onError("E_PERMISSION", "READ_EXTERNAL_STORAGE denied");
        });
    }
}

