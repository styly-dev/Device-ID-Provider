#if UNITY_2018_4_OR_NEWER
using System;
using System.Text.RegularExpressions;

namespace Styly.Device
{
    /// <summary>
    /// Public entry point to get a stable device identifier.
    /// Delegates to a platform-specific provider.
    /// </summary>
    public static class DeviceIdProvider
    {
        private static readonly IDeviceIdProvider s_impl = CreateImpl();

        /// <summary>
        /// Retrieves a stable GUID for the current execution platform.
        /// On Android, this will request permissions if needed without blocking the main thread,
        /// then invoke <paramref name="onSuccess"/> with the device ID.
        /// On other platforms, <paramref name="onSuccess"/> is called synchronously.
        /// </summary>
        /// <param name="onSuccess">Called with the device ID string when retrieval succeeds.</param>
        /// <param name="onError">Called with the exception if retrieval fails. If null, errors are logged via Debug.LogError.</param>
        public static void GetDeviceID(Action<string> onSuccess, Action<Exception> onError = null)
        {
            if (onSuccess == null)
                throw new ArgumentNullException(nameof(onSuccess));

            if (s_impl == null)
            {
                var ex = new PlatformNotSupportedException("DeviceIdProvider is not supported on this platform.");
                if (onError != null)
                    onError(ex);
                else
                    UnityEngine.Debug.LogError($"[DeviceIdProvider] {ex}");
                return;
            }
            s_impl.GetDeviceID(onSuccess, onError);
        }

        private static IDeviceIdProvider CreateImpl()
        {
            var p = UnityEngine.Application.platform;
            switch (p)
            {
                case UnityEngine.RuntimePlatform.Android:
                    return new AndroidDeviceIdProvider();
                case UnityEngine.RuntimePlatform.WindowsPlayer:
                case UnityEngine.RuntimePlatform.OSXPlayer:
                case UnityEngine.RuntimePlatform.WindowsEditor:
                case UnityEngine.RuntimePlatform.OSXEditor:
                    return new StandaloneDeviceIdProvider();
                default:
                    return new UnsupportedDeviceIdProvider();
            }
        }
    }

    internal interface IDeviceIdProvider
    {
        void GetDeviceID(Action<string> onSuccess, Action<Exception> onError);
    }

    internal static class DeviceIdRegexes
    {
        private const string GuidPatternBody = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        internal const string GuidPattern = "^" + GuidPatternBody + "$";
        internal const string GuidPngPattern = "^" + GuidPatternBody + "\\.png$";

        internal static readonly Regex GuidRegex = new Regex(GuidPattern, RegexOptions.Compiled);
        internal static readonly Regex GuidPngRegex = new Regex(GuidPngPattern, RegexOptions.Compiled);
    }
}
#endif
