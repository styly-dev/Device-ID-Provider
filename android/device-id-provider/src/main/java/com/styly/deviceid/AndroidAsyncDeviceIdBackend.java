package com.styly.deviceid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

/** Storage readiness is checked under the same UID that will resolve the device ID. */
final class AndroidAsyncDeviceIdBackend implements AsyncDeviceIdRequest.Backend {
    private final Context context;
    private Runnable unregister;

    AndroidAsyncDeviceIdBackend(Context context) {
        this.context = context;
    }

    @Override
    public void observe(Runnable onChanged) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageManager manager = context.getSystemService(StorageManager.class);
            StorageManager.StorageVolumeCallback callback = new StorageManager.StorageVolumeCallback() {
                @Override
                public void onStateChanged(StorageVolume volume) {
                    if (volume.isPrimary()) onChanged.run();
                }
            };
            manager.registerStorageVolumeCallback(Runnable::run, callback);
            unregister = () -> manager.unregisterStorageVolumeCallback(callback);
        } else {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    onChanged.run();
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
            filter.addDataScheme("file");
            context.registerReceiver(receiver, filter);
            unregister = () -> context.unregisterReceiver(receiver);
        }
    }

    @Override
    public DeviceIdResult lookup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DeviceIdResult.failure(DeviceIdStatus.UNSUPPORTED_API, false,
                    "STYLY Device ID requires Android API 29 or newer.");
        }
        try {
            if (!MediaAccess.canReadAllImages(context)) {
                return DeviceIdResult.failure(DeviceIdStatus.ACCESS_DENIED, false,
                        "Broad image read access is required to select or mint the canonical device ID.");
            }
            String state = Environment.getExternalStorageState();
            if (!Environment.MEDIA_MOUNTED.equals(state)
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) return null;

            // An aggregate external query can be empty while the primary volume is absent.
            // Probe that specific volume before allowing getOrCreate to consider minting.
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    new String[] {MediaStore.Images.Media._ID}, "_id = -1", null, null)) {
                if (cursor == null) {
                    return DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false,
                            "MediaStore readiness query returned a null cursor.");
                }
                cursor.getCount();
            }
            DeviceIdResult value = DeviceIdProvider.getOrCreate(context);
            // A volume can detach between the readiness probe and the actual lookup.
            return isRetryable(value) ? null : value;
        } catch (SecurityException error) {
            return DeviceIdResult.failure(DeviceIdStatus.ACCESS_DENIED, false, describe(error));
        } catch (RuntimeException error) {
            DeviceIdResult value = DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false, describe(error));
            return isRetryable(value) ? null : value;
        }
    }

    static boolean isRetryable(DeviceIdResult value) {
        // Android exposes this condition as IllegalArgumentException, not a public typed error.
        // Keep the compatibility mapping exact; other I/O failures must not be retried blindly.
        return value.getStatus() == DeviceIdStatus.IO_ERROR && !value.wasMintAttempted()
                && ("IllegalArgumentException: Volume external_primary not found".equals(
                        value.getDiagnosticMessage())
                || "IllegalArgumentException: Volume external_primary currently unavailable".equals(
                        value.getDiagnosticMessage()));
    }

    private static String describe(RuntimeException error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    @Override
    public void close() {
        if (unregister != null) {
            Runnable cleanup = unregister;
            unregister = null;
            cleanup.run();
        }
    }
}
