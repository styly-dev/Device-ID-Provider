# Device ID Provider
[![openupm](https://img.shields.io/npm/v/com.styly.device-id-provider?label=openupm&registry_uri=https://package.openupm.com)](https://openupm.com/packages/com.styly.device-id-provider/)


Device ID Provider is a Unity sample project that demonstrates how to obtain a stable, pseudo-anonymous GUID for the current device by using the [`Styly.Device`](Packages/com.styly.device-id-provider/Runtime/DeviceIdProvider.cs) runtime package. The project includes a small scene that prints the resolved identifier at startup and provides reference implementations for Android, Windows, and macOS.

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
using Styly.Device;
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
- The canonical MediaStore implementation is a Java Android library. The Unity C# provider is a thin JNI wrapper around the same AAR that native Android applications can consume.
- Candidate selection uses `date_added ASC, _id ASC` so multiple valid images have a deterministic winner. MediaStore does not provide an atomic cross-process compare-and-set operation, so simultaneous first-time callers can both insert an image; the final lookup and every later lookup converge on the same published winner.
- Runtime permissions:
  - API level ≤ 32: requests `READ_EXTERNAL_STORAGE`.
  - API level ≥ 33: requests `READ_MEDIA_IMAGES`.
  - API level ≥ 34: also requests `READ_MEDIA_VISUAL_USER_SELECTED` so partial photo
    access can be distinguished from full access. Partial access is rejected because it
    cannot establish the canonical cross-application ID.
  The Unity wrapper blocks until the permission is granted (with a timeout) and throws `UnauthorizedAccessException` if the user denies it. The native library itself never starts an Activity or requests permission UI, so it can be called from a headless service.
- `MANAGE_EXTERNAL_STORAGE` is also accepted when the host application already has all-files access. The library refuses to mint from a limited MediaStore view because an empty query without broad image access cannot prove that another application has not already created an ID.

### Native Android library

The source of truth for Android is the Gradle project under `android/`. It exposes:

```java
DeviceIdResult result = DeviceIdProvider.getOrCreate(applicationContext);
```

`DeviceIdResult` reports `SUCCESS`, `NOT_FOUND`, `ACCESS_DENIED`, `UNSUPPORTED_API`, or `IO_ERROR`, plus the selected ID and candidate count. Hosts own their permission UX and retry policy.

Build and test the library with JDK 17 and an Android SDK:

```bash
./android/gradlew -p android testReleaseUnitTest
./android/gradlew -p android :device-id-provider:syncUnityAar
```

The second command rebuilds the exact AAR committed at
`Packages/com.styly.device-id-provider/Plugins/Android/styly-device-id-provider.aar`.
The same release component can be installed into the local Maven repository with
`:device-id-provider:publishReleasePublicationToMavenLocal`. Configure a destination repository
in the Gradle publishing block before publishing it remotely.

### Windows and macOS

- The standalone provider writes the GUID to a text file located in the user's application data directory: `%LOCALAPPDATA%/Styly/Device-ID-Provider/device.id` on Windows and `~/Library/Application Support/Styly/Device-ID-Provider/device.id` on macOS.
- In the Unity Editor, the provider stores the GUID under an additional subdirectory derived from a stable hash of `Application.dataPath`. This isolates the device ID per project directory, ParrelSync clone, or Multiplayer Play Mode virtual player instead of sharing one Editor-wide file.
- If those special folders are unavailable (for example in restricted environments), the provider falls back to `Application.persistentDataPath`.
- The provider validates existing file contents and regenerates the GUID if the file is missing or corrupted.

### Unsupported platforms

Platforms other than those listed above (including iOS, WebGL, etc.) currently throw `PlatformNotSupportedException` when `GetDeviceID()` is called.

## Package structure

```
Packages/com.styly.device-id-provider/
├── Plugins/Android/
│   └── styly-device-id-provider.aar       # Native Android implementation used by Unity
├── Runtime/
│   ├── DeviceIdProvider.cs                # Entry point that chooses the implementation at runtime
│   ├── Providers/
│   │   ├── AndroidDeviceIdProvider.cs     # Thin Unity JNI wrapper for the Android AAR
│   │   ├── StandaloneDeviceIdProvider.cs  # File-based persistence for Windows/macOS
│   │   └── UnsupportedDeviceIdProvider.cs # Throws on other platforms
│   └── Internal/
│       └── AndroidBridge.cs               # Minimal Unity <-> Android JNI helpers
└── package.json                           # Package metadata
```

The native Android source, tests, AAR build, and Maven publication configuration live in
`android/device-id-provider/`.
