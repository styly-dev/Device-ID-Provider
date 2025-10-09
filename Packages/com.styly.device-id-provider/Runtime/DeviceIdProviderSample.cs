using UnityEngine;
using UnityEngine.UI;

namespace STYLY.DeviceIdProvider
{
    public class DeviceIdProviderSample : MonoBehaviour
    {
        [SerializeField] private Text output;
        private string _pendingText;

        public void OnGetDeviceId()
        {
            Debug.Log("[Sample] GetDeviceId clicked");
            DeviceIdProviderUnity.GetDeviceID(
                id =>
                {
                    Debug.Log($"[Sample] DeviceID: {id}");
                    _pendingText = id;
                },
                (code, msg) =>
                {
                    Debug.LogError($"[Sample] DeviceID Error: {code}: {msg}");
                    _pendingText = $"Error: {code}\n{msg}";
                }
            );
        }

        private void Update()
        {
            if (!string.IsNullOrEmpty(_pendingText) && output)
            {
                output.text = _pendingText;
                _pendingText = null;
            }
        }
    }
}
