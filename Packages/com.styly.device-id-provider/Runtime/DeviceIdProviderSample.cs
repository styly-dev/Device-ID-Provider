using UnityEngine;
using UnityEngine.UI;

namespace STYLY.DeviceIdProvider
{
    public class DeviceIdProviderSample : MonoBehaviour
    {
        [SerializeField] private Text output;

        public void OnGetDeviceId()
        {
            DeviceIdProviderUnity.GetDeviceID(
                id =>
                {
                    Debug.Log($"DeviceID: {id}");
                    if (output) output.text = id;
                },
                (code, msg) =>
                {
                    Debug.LogError($"DeviceID Error: {code}: {msg}");
                    if (output) output.text = $"Error: {code}\n{msg}";
                }
            );
        }
    }
}

