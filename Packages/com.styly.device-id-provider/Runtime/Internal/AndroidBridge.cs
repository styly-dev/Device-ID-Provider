using UnityEngine;

namespace Styly.Device
{
    internal static class AndroidBridge
    {
        public static int GetSdkInt()
        {
            using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
            {
                return version.GetStatic<int>("SDK_INT");
            }
        }

        public static AndroidJavaObject GetActivity()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            {
                return unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            }
        }

        public static AndroidJavaObject GetApplicationContext()
        {
            using (var activity = GetActivity())
            {
                return activity.Call<AndroidJavaObject>("getApplicationContext");
            }
        }

        public static bool HasAllFilesAccess(int sdk)
        {
            if (sdk < 30)
                return false;

            using (var environment = new AndroidJavaClass("android.os.Environment"))
            {
                return environment.CallStatic<bool>("isExternalStorageManager");
            }
        }
    }
}
