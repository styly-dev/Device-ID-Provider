#if UNITY_2018_4_OR_NEWER
using System;
using System.IO;
using System.Threading;
using UnityEngine;

namespace Styly.Device
{
    /// <summary>
    /// Unity wrapper around the canonical native Android Device ID implementation.
    /// </summary>
    internal sealed class AndroidDeviceIdProvider : IDeviceIdProvider
    {
        private const string NativeProviderClass = "com.styly.deviceid.DeviceIdProvider";
        private const string ReadExternalStoragePermission =
            "android.permission.READ_EXTERNAL_STORAGE";
        private const string ReadMediaImagesPermission =
            "android.permission.READ_MEDIA_IMAGES";
        private const string ReadSelectedMediaPermission =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
        private static readonly object AndroidLock = new object();

        public string GetDeviceID()
        {
            if (Application.platform != RuntimePlatform.Android)
                throw new PlatformNotSupportedException(
                    "DeviceIdProvider.GetDeviceID is supported on Android runtime only");

            lock (AndroidLock)
            {
                var sdk = AndroidBridge.GetSdkInt();
                if (sdk < 29)
                    throw new NotSupportedException("This implementation requires Android API 29+.");

                EnsurePermissionsOrThrow(sdk);

                try
                {
                    using (var context = AndroidBridge.GetApplicationContext())
                    using (var provider = new AndroidJavaClass(NativeProviderClass))
                    using (var result = provider.CallStatic<AndroidJavaObject>("getOrCreate", context))
                    {
                        return ResolveResult(result);
                    }
                }
                catch (Exception ex)
                {
                    Debug.LogError(
                        $"[DeviceIdProvider] Error in GetDeviceID: {ex.GetType().Name}: {ex.Message}\n{ex}");
                    throw;
                }
            }
        }

        private static string ResolveResult(AndroidJavaObject result)
        {
            if (result == null)
                throw new IOException("Native Android Device ID provider returned null.");

            string status;
            using (var statusObject = result.Call<AndroidJavaObject>("getStatus"))
            {
                status = statusObject?.Call<string>("name") ?? "IO_ERROR";
            }

            var diagnostic = result.Call<string>("getDiagnosticMessage") ?? string.Empty;
            switch (status)
            {
                case "SUCCESS":
                {
                    var deviceId = result.Call<string>("getDeviceId");
                    if (string.IsNullOrEmpty(deviceId) || !DeviceIdRegexes.GuidRegex.IsMatch(deviceId))
                        throw new IOException("Native Android Device ID provider returned an invalid GUID.");

                    var candidateCount = result.Call<int>("getCandidateCount");
                    if (candidateCount > 1)
                    {
                        Debug.LogWarning(
                            $"[DeviceIdProvider] {diagnostic} Candidate count: {candidateCount}.");
                    }
                    return deviceId;
                }
                case "ACCESS_DENIED":
                    throw new UnauthorizedAccessException(
                        string.IsNullOrEmpty(diagnostic)
                            ? "Shared image access is not granted."
                            : diagnostic);
                case "UNSUPPORTED_API":
                    throw new NotSupportedException(
                        string.IsNullOrEmpty(diagnostic)
                            ? "This implementation requires Android API 29+."
                            : diagnostic);
                case "NOT_FOUND":
                case "IO_ERROR":
                default:
                    throw new IOException(
                        string.IsNullOrEmpty(diagnostic)
                            ? $"Native Android Device ID operation failed with status {status}."
                            : diagnostic);
            }
        }

        private static void EnsurePermissionsOrThrow(int sdk)
        {
            try
            {
                var permission = sdk <= 32
                    ? ReadExternalStoragePermission
                    : ReadMediaImagesPermission;
                var requestedPermissions = sdk >= 34
                    ? new[] { ReadMediaImagesPermission, ReadSelectedMediaPermission }
                    : new[] { permission };
                if (!RequestAndWaitForPermission(
                        permission,
                        requestedPermissions,
                        () => AndroidBridge.HasAllFilesAccess(sdk)))
                {
                    throw new UnauthorizedAccessException(
                        $"{permission} full access not granted");
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"[DeviceIdProvider] Permission check/request failed: {ex}");
                throw;
            }
        }

        private static bool RequestAndWaitForPermission(
            string requiredPermission,
            string[] requestedPermissions,
            Func<bool> alternateGrantedChecker,
            int timeoutMs = 15000)
        {
            try
            {
                if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(requiredPermission))
                    return true;
                if (alternateGrantedChecker != null && alternateGrantedChecker())
                    return true;

                UnityEngine.Android.Permission.RequestUserPermissions(requestedPermissions);

                var stopwatch = System.Diagnostics.Stopwatch.StartNew();
                while (stopwatch.ElapsedMilliseconds < timeoutMs)
                {
                    if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(
                            requiredPermission))
                        return true;
                    if (alternateGrantedChecker != null && alternateGrantedChecker())
                        return true;
                    Thread.Sleep(100);
                }

                return UnityEngine.Android.Permission.HasUserAuthorizedPermission(
                           requiredPermission)
                       || (alternateGrantedChecker != null && alternateGrantedChecker());
            }
            catch
            {
                return false;
            }
        }
    }
}
#endif
