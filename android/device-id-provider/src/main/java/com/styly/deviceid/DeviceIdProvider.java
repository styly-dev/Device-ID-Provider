package com.styly.deviceid;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Native Android implementation of the shared STYLY Device ID contract.
 *
 * <p>This class is safe to call with an application Context from a service. It never starts an
 * Activity, displays permission UI, or waits for user interaction.</p>
 */
public final class DeviceIdProvider {
    private static final String TAG = "StylyDeviceId";
    private static final Object PROCESS_LOCK = new Object();

    private DeviceIdProvider() {
    }

    /**
     * Finds the current shared device ID without creating a new entry.
     */
    public static DeviceIdResult find(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DeviceIdResult.failure(
                    DeviceIdStatus.UNSUPPORTED_API,
                    false,
                    "STYLY Device ID requires Android API 29 or newer.");
        }

        try {
            Context safeContext = applicationContext(context);
            if (!MediaAccess.canReadAllImages(safeContext)) {
                return DeviceIdResult.failure(
                        DeviceIdStatus.ACCESS_DENIED,
                        false,
                        "Broad image read access is required to select the canonical device ID.");
            }
            MediaStoreRepository repository = repository(safeContext);
            CandidateSelector.Selection selection = repository.findWinner();
            if (!selection.isPresent()) {
                return DeviceIdResult.failure(
                        DeviceIdStatus.NOT_FOUND,
                        false,
                        "No valid STYLY Device ID image exists.");
            }
            return DeviceIdResult.success(
                    selection.deviceId,
                    selection.candidateCount,
                    false);
        } catch (SecurityException error) {
            return DeviceIdResult.failure(
                    DeviceIdStatus.ACCESS_DENIED,
                    false,
                    message(error));
        } catch (RuntimeException error) {
            return DeviceIdResult.failure(
                    DeviceIdStatus.IO_ERROR,
                    false,
                    message(error));
        }
    }

    /**
     * Finds the current shared device ID or creates and publishes a new one.
     *
     * <p>MediaStore does not provide a cross-process compare-and-set primitive. Concurrent callers
     * may both insert a candidate, so this method always performs a final deterministic lookup and
     * returns the selected winner. Later calls converge on the same winner even when duplicate
     * candidates remain.</p>
     */
    public static DeviceIdResult getOrCreate(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DeviceIdResult.failure(
                    DeviceIdStatus.UNSUPPORTED_API,
                    false,
                    "STYLY Device ID requires Android API 29 or newer.");
        }

        synchronized (PROCESS_LOCK) {
            boolean mintAttempted = false;
            try {
                Context safeContext = applicationContext(context);
                if (!MediaAccess.canReadAllImages(safeContext)) {
                    return DeviceIdResult.failure(
                            DeviceIdStatus.ACCESS_DENIED,
                            false,
                            "Broad image read access is required to select or mint "
                                    + "the canonical device ID.");
                }
                MediaStoreRepository repository = repository(safeContext);
                CandidateSelector.Selection existing = repository.findWinner();
                if (existing.isPresent()) {
                    return DeviceIdResult.success(
                            existing.deviceId,
                            existing.candidateCount,
                            false);
                }

                mintAttempted = true;
                String newDeviceId = UUID.randomUUID()
                        .toString()
                        .toLowerCase(Locale.ROOT);
                repository.createPng(newDeviceId);

                CandidateSelector.Selection resolved = repository.findWinner();
                if (!resolved.isPresent()) {
                    return DeviceIdResult.failure(
                            DeviceIdStatus.IO_ERROR,
                            true,
                            "A device ID image was created but could not be found.");
                }
                if (resolved.candidateCount > 1) {
                    Log.w(
                            TAG,
                            "Multiple device ID images were found after minting; "
                                    + "the deterministic oldest entry was selected.");
                }
                return DeviceIdResult.success(
                        resolved.deviceId,
                        resolved.candidateCount,
                        true);
            } catch (SecurityException error) {
                return DeviceIdResult.failure(
                        DeviceIdStatus.ACCESS_DENIED,
                        mintAttempted,
                        message(error));
            } catch (IOException | RuntimeException error) {
                return DeviceIdResult.failure(
                        DeviceIdStatus.IO_ERROR,
                        mintAttempted,
                        message(error));
            }
        }
    }

    private static Context applicationContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null.");
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext != null ? applicationContext : context;
    }

    private static MediaStoreRepository repository(Context context) {
        return new MediaStoreRepository(context.getContentResolver());
    }

    private static String message(Throwable error) {
        String detail = error.getMessage();
        return detail == null || detail.isEmpty()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + detail;
    }
}
