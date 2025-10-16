# Device ID Provider
[![openupm](https://img.shields.io/npm/v/com.styly.device-id-provider?label=openupm&registry_uri=https://package.openupm.com)](https://openupm.com/packages/com.styly.device-id-provider/)


Device ID Provider is a Unity sample project that demonstrates how to obtain a stable, pseudo-anonymous GUID for the current device by using the [`Styly.DeviceIdProvider`](Packages/com.styly.device-id-provider/Runtime/DeviceIdProvider.cs) runtime package. The project includes a small scene that prints the resolved identifier at startup and provides reference implementations for Android, Windows, and macOS.

## Requirements

- Unity 6000.0 or newer (per the package manifest).
- The project targets platforms where a provider implementation exists:
  - Android API level 29 or newer.
  - Windows Player and Editor.
  - macOS Player and Editor.

## Getting started

1. Open the project in Unity 6000.0 or later.
2. Load the sample scene and press Play (or build to the desired target platform).
3. The `GetDeviceID` MonoBehaviour resolves the GUID and prints it to the UI text element at startup:

```csharp
using Styly.DeviceIdProvider;
...
void Start()
{
    text.text = DeviceIdProvider.GetDeviceID();
}
```

## How GUID generation works

The runtime package selects a platform-specific implementation at runtime based on `Application.platform` and exposes it through the static `DeviceIdProvider.GetDeviceID()` API. Unsupported platforms throw `PlatformNotSupportedException` to make limitations explicit.

### Android (API 29+)

- Requires Android 10 (API 29) or newer because it stores the GUID as a one-pixel PNG inside the public MediaStore. This allows the identifier to survive app reinstalls as long as the user does not remove the shared image. 
- The image is created under `Pictures/Device-ID-Provider/` with a filename equal to the GUID plus the `.png` extension. The provider always returns the oldest matching entry to keep the identifier stable across runs.
- Runtime permissions:
  - API level ≤ 32: requests `READ_EXTERNAL_STORAGE`.
  - API level ≥ 33: requests `READ_MEDIA_IMAGES`.
  The call blocks until the permission is granted (with a timeout) and throws `UnauthorizedAccessException` if the user denies it.

### Windows and macOS

- The standalone provider writes the GUID to a text file located in the user's application data directory: `%LOCALAPPDATA%/Styly/Device-ID-Provider/device.id` on Windows and `~/Library/Application Support/Styly/Device-ID-Provider/device.id` on macOS.
- If those special folders are unavailable (for example in restricted environments), the provider falls back to `Application.persistentDataPath`.
- The provider validates existing file contents and regenerates the GUID if the file is missing or corrupted.

### Unsupported platforms

Platforms other than those listed above (including iOS, WebGL, etc.) currently throw `PlatformNotSupportedException` when `GetDeviceID()` is called.

## Package structure

```
Packages/com.styly.device-id-provider/
├── Runtime/
│   ├── DeviceIdProvider.cs                # Entry point that chooses the implementation at runtime
│   ├── Providers/
│   │   ├── AndroidDeviceIdProvider.cs     # MediaStore-based persistence for Android
│   │   ├── StandaloneDeviceIdProvider.cs  # File-based persistence for Windows/macOS
│   │   └── UnsupportedDeviceIdProvider.cs # Throws on other platforms
│   └── Internal/
│       ├── AndroidBridge.cs               # Unity <-> AndroidJavaObject helpers
│       └── Png1x1.cs                      # Embedded PNG payload written to MediaStore
└── package.json                           # Package metadata
```
