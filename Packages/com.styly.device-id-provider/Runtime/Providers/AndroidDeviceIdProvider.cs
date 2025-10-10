#if UNITY_2018_4_OR_NEWER
using System;
using System.IO;
using System.Threading;
using UnityEngine;

namespace Styly.DeviceIdProvider
{
    /// <summary>
    /// Android implementation that persists a GUID as a shared PNG in MediaStore so that it survives app reinstalls.
    /// </summary>
    internal sealed class AndroidDeviceIdProvider : IDeviceIdProvider
    {
        // For MediaStore.Images RELATIVE_PATH (no leading slash, trailing slash required)
        private const string ImagesRelativePath = "Pictures/Device-ID-Provider/";
        private const string PngMime = "image/png";
        private static readonly object AndroidLock = new object();

        public string GetDeviceID()
        {
            if (Application.platform != RuntimePlatform.Android)
                throw new PlatformNotSupportedException("DeviceIdProvider.GetDeviceID is supported on Android runtime only");

            lock (AndroidLock)
            {
                int sdk = AndroidBridge.GetSdkInt();

                if (sdk < 29)
                {
                    throw new NotSupportedException("This implementation requires Android API 29+.");
                }

                EnsurePermissionsOrThrow(sdk);

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
        }

        private static void EnsurePermissionsOrThrow(int sdk)
        {
            try
            {
                if (sdk <= 32)
                {
                    // SDK <= 32 uses READ_EXTERNAL_STORAGE
                    Require("android.permission.READ_EXTERNAL_STORAGE");
                }
                else // 33+
                {
                    // API 33+ uses READ_MEDIA_IMAGES
                    if (!RequestAndWaitForPermission("android.permission.READ_MEDIA_IMAGES"))
                    {
                        throw new UnauthorizedAccessException("Images permission not granted");
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"[DeviceIdProvider] Permission check/request failed: {ex}");
                throw;
            }
        }

        private static void Require(string permission)
        {
            if (!RequestAndWaitForPermission(permission))
                throw new UnauthorizedAccessException($"{permission} not granted");
        }

        /// <summary>
        /// If the permission is missing, request it and poll for a limited time.
        /// Returns true if granted. If altGrantedChecker returns true, it is also treated as granted.
        /// </summary>
        private static bool RequestAndWaitForPermission(string permission, Func<bool> altGrantedChecker = null, int timeoutMs = 15000)
        {
            try
            {
                if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission))
                    return true;
                if (altGrantedChecker != null && altGrantedChecker())
                    return true;

                UnityEngine.Android.Permission.RequestUserPermission(permission);

                var sw = System.Diagnostics.Stopwatch.StartNew();
                while (sw.ElapsedMilliseconds < timeoutMs)
                {
                    if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission))
                        return true;
                    if (altGrantedChecker != null && altGrantedChecker())
                        return true;

                    Thread.Sleep(100);
                }

                // Final check
                if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission))
                    return true;
                if (altGrantedChecker != null && altGrantedChecker())
                    return true;

                return false;
            }
            catch
            {
                return false;
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
                values.Call("put", "is_pending", 1);

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
                    var cv = new AndroidJavaObject("android.content.ContentValues");
                    cv.Call("put", "is_pending", 0);
                    int updated = resolver.Call<int>("update", uri, cv, null, null);
                    if (updated <= 0)
                        Debug.LogWarning("[DeviceIdProvider] Failed to clear IS_PENDING on created image");
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
