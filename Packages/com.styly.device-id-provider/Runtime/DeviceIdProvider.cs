#if UNITY_2018_4_OR_NEWER
using System;
using System.Text.RegularExpressions;

namespace Styly.DeviceIdProvider
{
    /// <summary>
    /// Public entry point to get a stable device identifier.
    /// Not a partial class. Delegates to a platform-specific provider.
    /// </summary>
    public static class DeviceIdProvider
    {
        private static readonly IDeviceIdProvider s_impl = CreateImpl();

        /// <summary>
        /// Returns a stable GUID for the current execution platform.
        /// </summary>
        public static string GetDeviceID()
        {
            if (s_impl == null)
                throw new PlatformNotSupportedException("DeviceIdProvider is not supported on this platform.");
            return s_impl.GetDeviceID();
        }

        private static IDeviceIdProvider CreateImpl()
        {
            // Choose provider at runtime using Application.platform to avoid platform #if branching.
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
        string GetDeviceID();
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
