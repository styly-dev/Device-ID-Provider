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

## Installation

Install the [OpenUPM CLI](https://github.com/openupm/openupm-cli), then run the following command from your Unity project directory:

```bash
npm install -g openupm-cli
openupm add com.styly.device-id-provider
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

#### Optional asynchronous API for native Android hosts

The Unity C# API and its synchronous behavior remain unchanged. Native Android
hosts that run during device boot can instead use:

```java
CompletableFuture<DeviceIdResult> request = DeviceIdProvider.getOrCreateAsync(
        applicationContext, 30_000L, 250L); // Example timeout and retry delay, in milliseconds.
request.thenAcceptAsync(result -> {
    if (result.getStatus() == DeviceIdStatus.SUCCESS) {
        useDeviceId(result.getDeviceId());
    } else {
        handleProviderFailure(result);
    }
}, applicationContext.getMainExecutor());
// Also handle exceptional completion (TimeoutException or unexpected setup failure).
// When the owning operation ends:
// request.cancel(false);
```

- Both durations must be positive. Register the mount observer before checking current state (API 30+: `StorageVolumeCallback`; API 29: `ACTION_MEDIA_MOUNTED`).
- Check broad image access, filesystem mount state, and a read-only primary-volume query before calling `getOrCreate`. A mount callback alone does not establish readiness.
- Retry unmounted storage and `IllegalArgumentException` from the fixed readiness probe or a lookup before minting, until the deadline. This is a bounded retry policy, not proof of an unmounted volume; no diagnostic-string matching is used. Notifications can trigger an earlier retry; lookups never overlap within a request.
- Permission denial, unsupported APIs, other I/O errors, and failures after a mint attempt are terminal. The library never opens permission UI.
- Timeout completes exceptionally with `TimeoutException`, retaining the last retryable exception as its cause when available; cancellation stops future attempts. Neither waits for a blocked observer registration, lookup, or observer removal.
- Cleanup is asynchronous. A registration that finishes after termination is also removed; blocked platform operations must return before their resources can be released.
- An already-started lookup is not interrupted and may still create an ID after timeout/cancellation. Late results are discarded; cancellation does not prove that no write occurred.
- Use completion handlers with an explicit executor. Do not block the main thread with `get()` / `join()` or manually complete the returned future.
- This API waits for volume accessibility, not every background media scan. Existing candidate selection and mint semantics are unchanged; only `SUCCESS` establishes that an ID was obtained.

The instrumented existing-ID test requires broad image access and an existing marker; otherwise it skips. Run it with permissions granted and the denied-access test with permissions revoked, checking raw instrumentation status for skips. The test APK targets API 34 independently of consuming applications. Boot-cycle verification must call this native API during real device startup; a warm instrumented pass is insufficient.

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
