package com.styly.deviceidprovider;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final public class DeviceIdProvider {

    private DeviceIdProvider() {}

    public interface Callback {
        void onSuccess(@NonNull String deviceId);
        void onError(@NonNull String code, @NonNull String message);
    }

    /**
     * Asynchronously obtains the device ID, showing permission or SAF UI if needed.
     */
    public static void getDeviceId(@NonNull Activity activity, @NonNull Callback cb) {
        if (Build.VERSION.SDK_INT >= 33) {
            cb.onError("E_PLUS_DISABLED", "API 33+ SAF implementation is disabled until instructed.");
        } else {
            MediaStoreHelper.getOrCreateDeviceId(activity, cb);
        }
    }

    /**
     * Returns device id if already provisioned (cached or resolvable without UI), else null.
     */
    @Nullable
    public static String getDeviceIdIfReady(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 33) return null; // Plus path disabled
        return MediaStoreHelper.peekDeviceId(context);
    }
}
