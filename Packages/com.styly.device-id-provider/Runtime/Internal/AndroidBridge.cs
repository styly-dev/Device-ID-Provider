#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using UnityEngine;

namespace Styly.DeviceIdProvider
{
    internal static class AndroidBridge
    {
        public static int GetSdkInt()
        {
            using (var ver = new AndroidJavaClass("android.os.Build$VERSION"))
            {
                return ver.GetStatic<int>("SDK_INT");
            }
        }

        public static int GetTargetSdkInt()
        {
            try
            {
                using (var activity = GetActivity())
                using (var appInfo = activity.Call<AndroidJavaObject>("getApplicationInfo"))
                {
                    // android.content.pm.ApplicationInfo.targetSdkVersion
                    return appInfo.Get<int>("targetSdkVersion");
                }
            }
            catch (Exception)
            {
                // Fallback: assume current device SDK (best effort)
                return GetSdkInt();
            }
        }

        public static AndroidJavaObject GetActivity()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            {
                return unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            }
        }

        public static AndroidJavaObject GetContentResolver()
        {
            using (var activity = GetActivity())
            {
                return activity.Call<AndroidJavaObject>("getContentResolver");
            }
        }

        public static AndroidJavaObject GetImagesExternalContentUri()
        {
            using (var media = new AndroidJavaClass("android.provider.MediaStore$Images$Media"))
            {
                return media.GetStatic<AndroidJavaObject>("EXTERNAL_CONTENT_URI");
            }
        }

        public static int CursorGetColumnIndex(AndroidJavaObject cursor, string name)
        {
            return cursor.Call<int>("getColumnIndex", name);
        }

        public static bool CursorMoveToFirst(AndroidJavaObject cursor)
        {
            return cursor.Call<bool>("moveToFirst");
        }

        public static bool CursorMoveToNext(AndroidJavaObject cursor)
        {
            return cursor.Call<bool>("moveToNext");
        }

        public static string CursorGetString(AndroidJavaObject cursor, int index)
        {
            return cursor.Call<string>("getString", index);
        }

        public static void OutputStreamWrite(AndroidJavaObject outputStream, byte[] buffer, int offset, int length)
        {
            outputStream.Call("write", buffer, offset, length);
        }

        public static string GetDownloadsAbsolutePath()
        {
            using (var env = new AndroidJavaClass("android.os.Environment"))
            {
                string dir = env.GetStatic<string>("DIRECTORY_DOWNLOADS");
                using (var file = env.CallStatic<AndroidJavaObject>("getExternalStoragePublicDirectory", dir))
                {
                    return file?.Call<string>("getAbsolutePath");
                }
            }
        }
    }
}
#endif
