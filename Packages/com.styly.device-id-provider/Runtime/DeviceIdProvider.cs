#if UNITY_2018_4_OR_NEWER
using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using UnityEngine;

namespace STYLY.DeviceIdProvider
{
    /// <summary>
    /// Provides a stable device-wide GUID using a shared PNG in Downloads/Device-ID-Provider/{guid}.png
    /// Android only. Throws on errors per spec.
    /// </summary>
    public static class DeviceIdProvider
    {
        private const string ImagesRelativePath = "Pictures/Device-ID-Provider/"; // Note: no leading slash, trailing slash required for MediaStore
        private const string FolderName = "Device-ID-Provider"; // for legacy (<29)
        private const string PngMime = "image/png";
        private static readonly Regex GuidPngRegex = new Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$", RegexOptions.Compiled);
        private static readonly object _lock = new object();

        /// <summary>
        /// Returns a stable GUID for the device, stored as a 1x1 PNG filename in shared Downloads.
        /// Throws on permission/IO/policy errors. Thread-safe within-process.
        /// </summary>
        public static string GetDeviceID()
        {
#if !UNITY_ANDROID || UNITY_EDITOR
            throw new PlatformNotSupportedException("DeviceIdProvider.GetDeviceID is supported on Android runtime only");
#else
            lock (_lock)
            {
                int sdk = AndroidBridge.GetSdkInt();
                EnsurePermissionsOrThrow(sdk);

                try
                {
                    if (sdk <= 28)
                        return Legacy_GetOrCreate_InDownloads();

                    // API 29+ via MediaStore.Images
                    var existing = MediaStore_FindOldestMatchingPng();
                    if (existing.success)
                        return existing.guid;

                    // None found: create
                    var createdGuid = Guid.NewGuid().ToString("D").ToLowerInvariant();
                    MediaStore_CreatePng(createdGuid);

                    // Re-query to minimize double creation
                    var after = MediaStore_FindOldestMatchingPng();
                    if (after.success)
                        return after.guid;

                    // If still not found, that's an error
                    throw new IOException("Failed to create and locate device ID PNG via MediaStore");
                }
                catch (Exception ex)
                {
                    Debug.LogError($"[DeviceIdProvider] Error in GetDeviceID: {ex.GetType().Name}: {ex.Message}\n{ex}");
                    throw;
                }
            }
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private static void EnsurePermissionsOrThrow(int sdk)
        {
            // Unity permission helper works only on main thread; assume API caller runs on main thread as per typical Unity usage.
            // If permission already granted, this is a no-op.
            bool needRead = false, needWrite = false, needReadImages = false;

            // Distinguish by both device OS and app target SDK to choose the correct runtime permission model
            int target = AndroidBridge.GetTargetSdkInt();
            if (sdk <= 28)
            {
                needRead = true; needWrite = true;
            }
            else if (sdk <= 32)
            {
                needRead = true;
            }
            else // sdk >= 33
            {
                if (target >= 33)
                {
                    needReadImages = true; // Android 13+ permission model for apps targeting 33+
                }
                else
                {
                    // Back-compat path: apps targeting <=32 on Android 13+ still rely on READ_EXTERNAL_STORAGE
                    needRead = true;
                }
            }

            try
            {
                if (needRead)
                {
                    var perm = "android.permission.READ_EXTERNAL_STORAGE";
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    {
                        UnityEngine.Android.Permission.RequestUserPermission(perm);
                    }
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    {
                        // On Android 13+ the platform may surface/grant READ_MEDIA_IMAGES instead.
                        if (sdk >= 33 && UnityEngine.Android.Permission.HasUserAuthorizedPermission("android.permission.READ_MEDIA_IMAGES"))
                        {
                            // treat as granted
                        }
                        else
                        {
                            throw new UnauthorizedAccessException("READ_EXTERNAL_STORAGE permission not granted");
                        }
                    }
                }

                if (needWrite)
                {
                    var perm = "android.permission.WRITE_EXTERNAL_STORAGE";
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    {
                        UnityEngine.Android.Permission.RequestUserPermission(perm);
                    }
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                        throw new UnauthorizedAccessException("WRITE_EXTERNAL_STORAGE permission not granted");
                }

                if (needReadImages)
                {
                    var perm = "android.permission.READ_MEDIA_IMAGES";
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    {
                        UnityEngine.Android.Permission.RequestUserPermission(perm);
                    }
                    // On some combinations, requesting READ_MEDIA_IMAGES might be ignored; accept READ_EXTERNAL_STORAGE if it's granted.
                    if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    {
                        if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission("android.permission.READ_EXTERNAL_STORAGE"))
                            throw new UnauthorizedAccessException("READ_MEDIA_IMAGES permission not granted");
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"[DeviceIdProvider] Permission check/request failed: {ex}");
                throw;
            }
        }

        private static (bool success, string guid) MediaStore_FindOldestMatchingPng()
        {
            var resolver = AndroidBridge.GetContentResolver();
            var images = AndroidBridge.GetImagesExternalContentUri();

            // Build query: RELATIVE_PATH LIKE 'Download/Device-ID-Provider/%' AND _display_name LIKE '%.png'
            string[] projection = { "_id", "_display_name", "date_added", "relative_path" };
            string selection;
            string[] selectionArgs;

            int sdk = AndroidBridge.GetSdkInt();
            if (sdk >= 29)
            {
                selection = "relative_path LIKE ? AND _display_name LIKE ?";
                selectionArgs = new[] { ImagesRelativePath + "%", "%.png" };
            }
            else
            {
                // Not used for <29 path, but keep for completeness
                selection = "_display_name LIKE ?";
                selectionArgs = new[] { "%.png" };
            }

            using (var cursor = resolver.Call<AndroidJavaObject>("query", images, projection, selection, selectionArgs, "date_added ASC"))
            {
                if (cursor == null)
                    throw new IOException("MediaStore query returned null cursor");

                int idxId = AndroidBridge.CursorGetColumnIndex(cursor, "_id");
                int idxName = AndroidBridge.CursorGetColumnIndex(cursor, "_display_name");

                if (AndroidBridge.CursorMoveToFirst(cursor))
                {
                    do
                    {
                        string name = AndroidBridge.CursorGetString(cursor, idxName);
                        if (string.IsNullOrEmpty(name)) continue;
                        if (!GuidPngRegex.IsMatch(name))
                        {
                            throw new InvalidDataException($"Found PNG in target folder but filename not GUID: {name}");
                        }
                        string guid = name.Substring(0, name.Length - 4);
                        return (true, guid);
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
                values.Call("put", "is_pending", 1);
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
                    var cv = new AndroidJavaObject("android.content.ContentValues");
                    cv.Call("put", "is_pending", 0);
                    int updated = resolver.Call<int>("update", uri, cv, null, null);
                    if (updated <= 0)
                        Debug.LogWarning("[DeviceIdProvider] Failed to clear IS_PENDING on created image");
                }
            }
            catch
            {
                // Best effort cleanup if insert succeeded but writing failed
                if (uri != null)
                {
                    try { resolver.Call<int>("delete", uri, null, null); } catch { /* ignore */ }
                }
                throw;
            }
        }

        private static string Legacy_GetOrCreate_InDownloads()
        {
            // Get /storage/emulated/0/Download/Device-ID-Provider
            string downloadsPath = AndroidBridge.GetDownloadsAbsolutePath();
            if (string.IsNullOrEmpty(downloadsPath))
                throw new IOException("Unable to determine external Downloads directory");

            string folder = Path.Combine(downloadsPath, FolderName);
            Directory.CreateDirectory(folder);

            // Find existing *.png and pick oldest
            var files = Directory.GetFiles(folder, "*.png");
            if (files.Length > 0)
            {
                var fi = files.Select(p => new FileInfo(p))
                    .OrderBy(f => f.CreationTimeUtc != DateTime.MinValue ? f.CreationTimeUtc : f.LastWriteTimeUtc)
                    .First();
                var name = fi.Name; // includes extension
                if (!GuidPngRegex.IsMatch(name))
                    throw new InvalidDataException($"Found PNG in target folder but filename not GUID: {name}");
                return name.Substring(0, name.Length - 4);
            }

            // Create new
            string guid = Guid.NewGuid().ToString("D").ToLowerInvariant();
            string path = Path.Combine(folder, guid + ".png");
            File.WriteAllBytes(path, Png1x1.Bytes);

            // After create, re-scan to minimize duplicates
            files = Directory.GetFiles(folder, "*.png");
            if (files.Length == 0)
                throw new IOException("Failed to create device ID PNG in legacy path");

            var chosen = files.Select(p => new FileInfo(p))
                .OrderBy(f => f.CreationTimeUtc != DateTime.MinValue ? f.CreationTimeUtc : f.LastWriteTimeUtc)
                .First();
            var chosenName = chosen.Name;
            if (!GuidPngRegex.IsMatch(chosenName))
                throw new InvalidDataException($"Found PNG in target folder but filename not GUID: {chosenName}");
            return chosenName.Substring(0, chosenName.Length - 4);
        }
#endif // UNITY_ANDROID && !UNITY_EDITOR
    }
}
#endif
