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

Both durations must be positive. The request registers a mount observer before
its first readiness check. It checks broad image access, mount state, and a
read-only query against the primary MediaStore volume before calling the existing
`getOrCreate`. A mounted filesystem alone does not imply that MediaStore is ready.
API 30+ uses `StorageVolumeCallback`; API 29 uses `ACTION_MEDIA_MOUNTED`.

Storage that is not mounted, or the known Android `external_primary` volume-not-found /
currently-unavailable error before any mint attempt, is retried until the deadline.
Notifications can trigger a recheck before the retry timer. Repeated notifications
are coalesced; one request never runs overlapping lookups. The existing native
process lock still serializes `getOrCreate` across requests. General I/O errors,
permission denial, unsupported APIs, and failures after a mint attempt are terminal
`DeviceIdResult` values; permission UI is never opened. The Android volume error
mapping is deliberately exact because there is no public typed exception for it;
unrecognized errors remain terminal rather than being retried speculatively.

Timeout completes the future exceptionally with `TimeoutException`, independently
of an in-flight lookup. Cancellation stops observation and future attempts, but
neither cancellation nor timeout can interrupt an already-started lookup attempt,
undo a created ID, or establish that no write occurred. An in-flight attempt may
still reach its write step after cancellation. Late results are discarded.
Observers and scheduling resources are released on completion, cancellation, or
failure; an in-flight worker exits after its operation returns. Use async completion
handlers with an explicit executor for UI work and never block the main thread with
`get()` or `join()`. Keep the returned future for observation/cancellation; do not
manually complete it.

This API waits for volume accessibility, not completion of every background media
scan. Existing candidate selection and mint semantics are unchanged. A successful
Provider result, not a mount callback, establishes that an ID was obtained.

The instrumented existing-ID test requires image-read access and a pre-existing
marker; it skips when that precondition is absent. Run the denied-access test with
permissions revoked and the existing-ID test with permissions granted, and check
instrumentation status to distinguish passes from skips. The test APK targets
API 34 independently of the consuming application's target SDK.

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
