package com.styly.deviceid;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

/** Checks readiness under the caller's UID before invoking the synchronous provider once. */
final class AndroidAsyncDeviceIdBackend implements AsyncDeviceIdRequest.Backend {
    private final Context context;
    private volatile Throwable lastRetryCause;

    AndroidAsyncDeviceIdBackend(Context context) {
        this.context = context;
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
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                lastRetryCause = new IllegalStateException("Primary storage is " + state);
                return null;
            }
            // A mounted filesystem does not establish MediaStore readiness. Never infer absence
            // of an existing ID from an aggregate query while the primary volume is unavailable.
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    new String[] {MediaStore.Images.Media._ID}, "_id = -1", null, null)) {
                if (cursor == null) {
                    return DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false,
                            "MediaStore readiness query returned a null cursor.");
                }
                cursor.getCount();
            } catch (IllegalArgumentException notReady) {
                lastRetryCause = notReady;
                return null;
            }
            // Readiness retries end here; preserve every result of the existing provider.
            return DeviceIdProvider.getOrCreate(context);
        } catch (SecurityException error) {
            return DeviceIdResult.failure(DeviceIdStatus.ACCESS_DENIED, false, error.toString());
        } catch (RuntimeException error) {
            return DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false, error.toString());
        }
    }

    @Override
    public Throwable lastRetryCause() {
        return lastRetryCause;
    }
}
