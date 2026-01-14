using Styly.Device;
using System.Collections;
using System.Collections.Generic;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.UI;

public class GetDeviceID : MonoBehaviour
{
    [SerializeField] 
    private Text text = null;
    
    private bool isProcessing = false;
    
    // Start is called once before the first execution of Update after the MonoBehaviour is created
    void Start()
    {
        text.text = DeviceIdProvider.GetDeviceID();
    }
    // Update is called once per frame
    void Update()
    {
        
    }
}
