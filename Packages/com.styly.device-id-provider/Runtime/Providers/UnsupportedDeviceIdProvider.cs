#if UNITY_2018_4_OR_NEWER
using System;

namespace Styly.Device
{
    internal sealed class UnsupportedDeviceIdProvider : IDeviceIdProvider
    {
        public string GetDeviceID()
        {
            throw new PlatformNotSupportedException("DeviceIdProvider.GetDeviceID is not yet supported on this platform.");
        }
    }
}
#endif
