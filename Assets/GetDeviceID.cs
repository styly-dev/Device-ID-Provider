using STYLY.DeviceIdProvider;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

public class GetDeviceID : MonoBehaviour
{
    [SerializeField] 
    private Text text = null;
    // Start is called once before the first execution of Update after the MonoBehaviour is created
    IEnumerator Start()
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        // Request needed runtime permissions before calling into DeviceIdProvider
        int osSdk = 0;
        using (var ver = new AndroidJavaClass("android.os.Build$VERSION"))
        {
            osSdk = ver.GetStatic<int>("SDK_INT");
        }
        int targetSdk = osSdk;
        using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
        using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
        using (var appInfo = activity.Call<AndroidJavaObject>("getApplicationInfo"))
        {
            targetSdk = appInfo.Get<int>("targetSdkVersion");
        }

        var required = new List<string>();
        if (osSdk <= 28)
        {
            required.Add("android.permission.READ_EXTERNAL_STORAGE");
            required.Add("android.permission.WRITE_EXTERNAL_STORAGE");
        }
        else if (osSdk <= 32)
        {
            required.Add("android.permission.READ_EXTERNAL_STORAGE");
        }
        else // 33+
        {
            if (targetSdk >= 33)
                required.Add("android.permission.READ_MEDIA_IMAGES");
            else
                required.Add("android.permission.READ_EXTERNAL_STORAGE");
        }

        var missing = required.FindAll(p => !UnityEngine.Android.Permission.HasUserAuthorizedPermission(p));
        if (missing.Count > 0)
        {
            var callbacks = new UnityEngine.Android.PermissionCallbacks();
            int responses = 0;
            System.Action maybeContinue = () => { responses++; };
            callbacks.PermissionGranted += _ => maybeContinue();
            callbacks.PermissionDenied += _ => maybeContinue();
            callbacks.PermissionDeniedAndDontAskAgain += _ => maybeContinue();

            UnityEngine.Android.Permission.RequestUserPermissions(missing.ToArray(), callbacks);
            while (responses < missing.Count) yield return null;
        }

        // Effective permission check with cross-version fallback
        bool Has(string p) => UnityEngine.Android.Permission.HasUserAuthorizedPermission(p);
        bool canReadImages;
        if (osSdk <= 28)
        {
            canReadImages = Has("android.permission.READ_EXTERNAL_STORAGE") && Has("android.permission.WRITE_EXTERNAL_STORAGE");
        }
        else if (osSdk <= 32)
        {
            canReadImages = Has("android.permission.READ_EXTERNAL_STORAGE");
        }
        else // 33+
        {
            if (targetSdk >= 33)
                canReadImages = Has("android.permission.READ_MEDIA_IMAGES") || Has("android.permission.READ_EXTERNAL_STORAGE");
            else
                canReadImages = Has("android.permission.READ_EXTERNAL_STORAGE") || Has("android.permission.READ_MEDIA_IMAGES");
        }

        if (!canReadImages)
        {
            if (text != null) text.text = "Permission denied. Cannot get Device ID.";
            yield break;
        }
#endif

        string id = null;
        try
        {
            id = DeviceIdProvider.GetDeviceID();
        }
        catch (System.Exception e)
        {
            Debug.LogError($"[Sample] GetDeviceID failed: {e}");
            if (text != null) text.text = $"Error: {e.Message}";
            yield break;
        }

        if (text != null) text.text = id; // avoid NRE if Text is not assigned
        Debug.Log(id);
    }

    // Update is called once per frame
    void Update()
    {
        
    }
}
