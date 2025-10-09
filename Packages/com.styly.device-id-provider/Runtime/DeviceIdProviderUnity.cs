using System;
using System.Threading;
#if UNITY_ANDROID
using UnityEngine;
#endif

namespace STYLY.DeviceIdProvider
{
    public static class DeviceIdProviderUnity
    {
        private static SynchronizationContext _unityContext;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void Init()
        {
#if UNITY_ANDROID
            _unityContext = SynchronizationContext.Current;
            Debug.Log("[DeviceIdProviderUnity] Initialized and captured Unity SynchronizationContext");
#endif
        }

        private static void RunOnMainThread(Action a)
        {
            if (a == null) return;
            if (_unityContext != null)
            {
                _unityContext.Post(_ =>
                {
                    try { a(); } catch (Exception ex) { Debug.LogError($"[DeviceIdProviderUnity] Callback exception: {ex}"); }
                }, null);
            }
            else
            {
                // Fallback if context is not captured (shouldn't happen on Android runtime)
                try { a(); } catch (Exception ex) { Debug.LogError($"[DeviceIdProviderUnity] (NoContext) Callback exception: {ex}"); }
            }
        }

        public static void GetDeviceID(Action<string> onSuccess, Action<string, string> onError)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var cls = new AndroidJavaClass("com.styly.deviceidprovider.DeviceIdProvider"))
                {
                    Debug.Log("[DeviceIdProviderUnity] Calling Java getDeviceId...");
                    var callback = new DeviceIdCallbackProxy(
                        id => RunOnMainThread(() =>
                        {
                            Debug.Log($"[DeviceIdProviderUnity] onSuccess: {id}");
                            onSuccess?.Invoke(id);
                        }),
                        (code, msg) => RunOnMainThread(() =>
                        {
                            Debug.LogError($"[DeviceIdProviderUnity] onError: {code}: {msg}");
                            onError?.Invoke(code, msg);
                        })
                    );
                    cls.CallStatic("getDeviceId", activity, callback);
                }
            }
            catch (Exception e)
            {
                Debug.LogError($"[DeviceIdProviderUnity] Exception: {e}");
                RunOnMainThread(() => onError?.Invoke("E_UNITY", e.Message));
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
