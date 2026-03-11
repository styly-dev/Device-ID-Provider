using Styly.Device;
using UnityEngine;
using UnityEngine.UI;

public class GetDeviceID : MonoBehaviour
{
    [SerializeField]
    private Text text = null;

    void Start()
    {
        text.text = "Retrieving Device ID...";
        DeviceIdProvider.GetDeviceID(
            onSuccess: id => text.text = id,
            onError: ex =>
            {
                Debug.LogError($"[GetDeviceID] Failed to get device ID: {ex}");
                text.text = $"Error: {ex.Message}";
            });
    }
}
