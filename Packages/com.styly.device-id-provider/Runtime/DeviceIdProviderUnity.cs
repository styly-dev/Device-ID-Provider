using System;
#if UNITY_ANDROID
using UnityEngine;
#endif

namespace STYLY.DeviceIdProvider
{
    public static class DeviceIdProviderUnity
    {
        public static void GetDeviceID(Action<string> onSuccess, Action<string, string> onError)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var cls = new AndroidJavaClass("com.styly.deviceidprovider.DeviceIdProvider"))
                {
                    var callback = new DeviceIdCallbackProxy(onSuccess, onError);
                    cls.CallStatic("getDeviceId", activity, callback);
                }
            }
            catch (Exception e)
            {
                onError?.Invoke("E_UNITY", e.Message);
            }
#else
            onError?.Invoke("E_PLATFORM", "Android runtime only");
#endif
        }

#if UNITY_ANDROID
        private class DeviceIdCallbackProxy : AndroidJavaProxy
        {
            private readonly Action<string> _onSuccess;
            private readonly Action<string, string> _onError;

            public DeviceIdCallbackProxy(Action<string> onSuccess, Action<string, string> onError)
                : base("com.styly.deviceidprovider.DeviceIdProvider$Callback")
            {
                _onSuccess = onSuccess;
                _onError = onError;
            }

            public void onSuccess(string deviceId)
            {
                _onSuccess?.Invoke(deviceId);
            }

            public void onError(string code, string message)
            {
                _onError?.Invoke(code, message);
            }
        }
#endif
    }
}

