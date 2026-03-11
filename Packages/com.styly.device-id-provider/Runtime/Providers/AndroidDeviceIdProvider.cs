#if UNITY_2018_4_OR_NEWER
using System;
using System.IO;
using UnityEngine;

namespace Styly.Device
{
    /// <summary>
    /// Android implementation that persists a GUID as a shared PNG in MediaStore so that it survives app reinstalls.
    /// </summary>
    internal sealed class AndroidDeviceIdProvider : IDeviceIdProvider
    {
        // For MediaStore.Images RELATIVE_PATH (no leading slash, trailing slash required)
        private const string ImagesRelativePath = "Pictures/Device-ID-Provider/";
        private const string PngMime = "image/png";

        public void GetDeviceID(Action<string> onSuccess, Action<Exception> onError)
        {
            if (Application.platform != RuntimePlatform.Android)
            {
                InvokeError(onError, new PlatformNotSupportedException("DeviceIdProvider.GetDeviceID is supported on Android runtime only"));
                return;
            }

            int sdk;
            try
            {
                sdk = AndroidBridge.GetSdkInt();
            }
            catch (Exception ex)
            {
                InvokeError(onError, ex);
                return;
            }

            if (sdk < 29)
            {
                InvokeError(onError, new NotSupportedException("This implementation requires Android API 29+."));
                return;
            }

            string permission = GetRequiredPermission(sdk);

            // If permission already granted, resolve immediately
            if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission))
            {
                try
                {
                    onSuccess(ResolveDeviceId());
                }
                catch (Exception ex)
                {
                    InvokeError(onError, ex);
                }
                return;
            }

            // Request permission via callbacks — does NOT block the main thread
            RequestPermission(permission,
                onGranted: () =>
                {
                    try
                    {
                        onSuccess(ResolveDeviceId());
                    }
                    catch (Exception ex)
                    {
                        InvokeError(onError, ex);
                    }
                },
                onDenied: () =>
                {
                    InvokeError(onError, new UnauthorizedAccessException($"Permission '{permission}' was not granted by the user."));
                });
        }

        private static string GetRequiredPermission(int sdk)
        {
            if (sdk <= 32)
                return "android.permission.READ_EXTERNAL_STORAGE";
            else
                return "android.permission.READ_MEDIA_IMAGES";
        }

        /// <summary>
        /// Requests a permission using Unity's PermissionCallbacks.
        /// Does NOT block the main thread. Invokes onGranted or onDenied when the user responds.
        /// </summary>
        private static void RequestPermission(string permission, Action onGranted, Action onDenied)
        {
            var callbacks = new UnityEngine.Android.PermissionCallbacks();
            callbacks.PermissionGranted += _ => onGranted();
            callbacks.PermissionDenied += _ => onDenied();
            callbacks.PermissionDeniedAndDontAskAgain += _ => onDenied();

            UnityEngine.Android.Permission.RequestUserPermission(permission, callbacks);
        }

        private static void InvokeError(Action<Exception> onError, Exception ex)
        {
            if (onError != null)
                onError(ex);
            else
                Debug.LogError($"[DeviceIdProvider] {ex}");
        }

        /// <summary>
        /// Core logic: find or create the device ID PNG via MediaStore.
        /// Must be called only after permissions are confirmed.
        /// </summary>
        private static string ResolveDeviceId()
        {
            try
            {
                // API 29+ via MediaStore.Images
                var existing = MediaStore_FindOldestMatchingPng();
                if (existing.success)
                    return existing.guid;

                // Not found -> create a new GUID entry
                var createdGuid = Guid.NewGuid().ToString("D").ToLowerInvariant();
                MediaStore_CreatePng(createdGuid);

                // Re-query to minimize races; converge on the oldest entry
                var after = MediaStore_FindOldestMatchingPng();
                if (after.success)
                    return after.guid;

                throw new IOException("Failed to create and locate device ID PNG via MediaStore");
            }
            catch (Exception ex)
            {
                Debug.LogError($"[DeviceIdProvider] Error in GetDeviceID: {ex.GetType().Name}: {ex.Message}\n{ex}");
                throw;
            }
        }

        private static (bool success, string guid) MediaStore_FindOldestMatchingPng()
        {
            var resolver = AndroidBridge.GetContentResolver();
            var images = AndroidBridge.GetImagesExternalContentUri();

            // RELATIVE_PATH LIKE 'Pictures/Device-ID-Provider/%' AND _display_name LIKE '%.png'
            // Sort by date_added ASC (pick the oldest)
            string[] projection = { "_display_name" };
            const string selection = "relative_path LIKE ? AND _display_name LIKE ?";
            string[] selectionArgs = { ImagesRelativePath + "%", "%.png" };

            using (var cursor = resolver.Call<AndroidJavaObject>("query", images, projection, selection, selectionArgs, "date_added ASC"))
            {
                if (cursor == null)
                    throw new IOException("MediaStore query returned null cursor");

                int idxName = AndroidBridge.CursorGetColumnIndex(cursor, "_display_name");

                if (AndroidBridge.CursorMoveToFirst(cursor))
                {
                    do
                    {
                        string name = AndroidBridge.CursorGetString(cursor, idxName);
                        if (string.IsNullOrEmpty(name)) continue;
                        if (DeviceIdRegexes.GuidPngRegex.IsMatch(name))
                        {
                            string guid = name.Substring(0, name.Length - 4);
                            return (true, guid);
                        }
                        // Skip PNGs that are not GUID-named
                    } while (AndroidBridge.CursorMoveToNext(cursor));
                }
            }

            return (false, null);
        }

        private static void MediaStore_CreatePng(string guid)
        {
            if (string.IsNullOrEmpty(guid)) throw new ArgumentNullException(nameof(guid));
            var resolver = AndroidBridge.GetContentResolver();
            var images = AndroidBridge.GetImagesExternalContentUri();

            var values = new AndroidJavaObject("android.content.ContentValues");
            values.Call("put", "_display_name", guid + ".png");
            values.Call("put", "mime_type", PngMime);
            values.Call("put", "relative_path", ImagesRelativePath);

            int sdk = AndroidBridge.GetSdkInt();
            bool needsPending = sdk >= 29 && sdk <= 30; // Android 10-11

            if (needsPending)
            {
                using (var one = new AndroidJavaObject("java.lang.Integer", 1))
                {
                    values.Call("put", "is_pending", one);
                }
            }

            AndroidJavaObject uri = null;
            try
            {
                uri = resolver.Call<AndroidJavaObject>("insert", images, values);
                if (uri == null) throw new IOException("ContentResolver.insert returned null Uri");

                using (var os = resolver.Call<AndroidJavaObject>("openOutputStream", uri))
                {
                    if (os == null) throw new IOException("openOutputStream returned null");
                    var bytes = Png1x1.Bytes;
                    AndroidBridge.OutputStreamWrite(os, bytes, 0, bytes.Length);
                    os.Call("flush");
                }

                if (needsPending)
                {
                    using (var cv = new AndroidJavaObject("android.content.ContentValues"))
                    using (var zero = new AndroidJavaObject("java.lang.Integer", 0))
                    {
                        cv.Call("put", "is_pending", zero);
                        int updated = resolver.Call<int>("update", uri, cv, null, null);
                        if (updated <= 0)
                            Debug.LogWarning("[DeviceIdProvider] Failed to clear IS_PENDING on created image");
                    }
                }
            }
            catch
            {
                // Best-effort cleanup on write failure
                if (uri != null)
                {
                    try { resolver.Call<int>("delete", uri, null, null); } catch { /* ignore */ }
                }
                throw;
            }
        }
    }
}
#endif
