#if UNITY_2018_4_OR_NEWER
using System;

namespace Styly.Device
{
    internal sealed class UnsupportedDeviceIdProvider : IDeviceIdProvider
    {
        public void GetDeviceID(Action<string> onSuccess, Action<Exception> onError)
        {
            var ex = new PlatformNotSupportedException("DeviceIdProvider.GetDeviceID is not yet supported on this platform.");
            if (onError != null)
                onError(ex);
            else
                UnityEngine.Debug.LogError($"[DeviceIdProvider] {ex}");
        }
    }
}
#endif
