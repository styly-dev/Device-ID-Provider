# Device ID Provider
[![openupm](https://img.shields.io/npm/v/com.styly.device-id-provider?label=openupm&registry_uri=https://package.openupm.com)](https://openupm.com/packages/com.styly.device-id-provider/)

[English](README.md) | [日本語](README.ja.md)

Device ID Provider is a Unity sample project that demonstrates how to obtain a stable pseudonymous identifier (GUID) for the current device using the [`Styly.Device`](Packages/com.styly.device-id-provider/Runtime/DeviceIdProvider.cs) runtime package. It includes a small scene that displays the identifier at startup, along with reference implementations for Android, Windows, and macOS.

For an overview of device IDs, storage locations, and operational guidance, see [What is a device ID?](docs/device-id.en.md).

## Requirements

- Unity 6000.0 or later.
- The following platforms have provider implementations:
  - Android API level 29 or later.
  - Windows Player and Editor.
  - macOS Player and Editor.

## Getting started

1. Open the project in Unity 6000.0 or later.
2. Load the sample scene and press Play.
3. The `GetDeviceID` MonoBehaviour retrieves a GUID at startup and displays it in a UI text element.

```csharp
using Styly.Device;
...
void Start()
{
    text.text = DeviceIdProvider.GetDeviceID();
}
```

## Installation

Install the [OpenUPM CLI](https://github.com/openupm/openupm-cli) and run the following commands from your Unity project directory.

```bash
npm install -g openupm-cli
openupm add com.styly.device-id-provider
```

## How GUID generation works

The runtime package selects a platform-specific implementation based on `Application.platform` at runtime and exposes it through the static `DeviceIdProvider.GetDeviceID()` API. On unsupported platforms, it throws `PlatformNotSupportedException` to make the limitation explicit.

### Android (API 29 or later)

- The GUID is stored as a one-pixel PNG in the shared MediaStore, which requires Android 10 (API 29) or later. The identifier can survive app reinstalls as long as the user does not delete the shared image.
- The image is created in `Pictures/Device-ID-Provider/`, with the GUID as its filename and a `.png` extension. The provider always returns the oldest matching entry to keep the identifier stable across runs.
- The reference Android MediaStore implementation is a Java Android library. The Unity C# provider is a thin JNI wrapper around the same AAR that native Android apps can also use.
- Candidates are selected using `date_added ASC, _id ASC` to ensure deterministic selection when multiple valid images exist. MediaStore has no atomic cross-process compare-and-update operation, so concurrent first calls may both insert an image. The final lookup and all subsequent lookups converge on the same published image.
- Runtime permissions:
  - API level 32 or lower: requests `READ_EXTERNAL_STORAGE`.
  - API level 33 or higher: requests `READ_MEDIA_IMAGES`.
  - API level 34 or higher: also requests `READ_MEDIA_VISUAL_USER_SELECTED` to distinguish partial photo access from full access. Partial access is rejected because it cannot establish a canonical ID shared across apps.

  The Unity wrapper waits for permission with a timeout and throws `UnauthorizedAccessException` if the user denies it. The native library itself does not launch an Activity or display permission prompts, so it can also be called from a service without a UI.
- `MANAGE_EXTERNAL_STORAGE` is also accepted if the host app already has all-files access. Without full image access, an empty query result cannot prove that another app has not already created an ID, so the library refuses to create a new ID from a MediaStore view with limited visibility.

### Native Android library

The Gradle project under `android/` is the source of truth for the Android implementation. It provides the following API:

```java
DeviceIdResult result = DeviceIdProvider.getOrCreate(applicationContext);
```

`DeviceIdResult` returns one of the statuses `SUCCESS`, `NOT_FOUND`, `ACCESS_DENIED`, `UNSUPPORTED_API`, or `IO_ERROR`, along with the selected ID and candidate count. The host app manages permission UI and retry policy.

#### Optional asynchronous API for native Android hosts

The Unity C# API and its synchronous behavior remain unchanged. Native Android hosts that run during device startup can use the following API instead:

```java
CompletableFuture<DeviceIdResult> request = DeviceIdProvider.getOrCreateAsync(
        applicationContext, 30_000L, 250L); // Example timeout and retry interval in milliseconds.
request.thenAcceptAsync(result -> {
    if (result.getStatus() == DeviceIdStatus.SUCCESS) {
        useDeviceId(result.getDeviceId());
    } else {
        handleProviderFailure(result);
    }
}, applicationContext.getMainExecutor());
// Also handle exceptional completion (TimeoutException or unexpected setup failures).
// When the operation that owns this request ends:
// request.cancel(false);
```

- Both duration arguments must be positive. At the specified retry interval, the API checks full image access, mount state, and a read-only query against the primary volume. It does not register a mount observer.
- If storage is not mounted or the fixed readiness probe returns `IllegalArgumentException`, it retries until the deadline. A mounted filesystem alone does not establish MediaStore readiness.
- Once the readiness check succeeds, it calls the existing `getOrCreate` once and returns its result, including `IO_ERROR`. It does not retry internally if the volume subsequently disconnects. The host can start a new request.
- Permission denial, an unsupported API level, or other readiness-check failures terminate the request immediately. The library never opens permission UI.
- An independent timer completes the Future with `TimeoutException` even if the lookup is blocked. Its cause preserves the most recent readiness-check exception or unmounted state.
- Cancellation stops future attempts and releases the retry wait. Neither timeout nor cancellation interrupts a lookup that has already started, rolls back a created ID, or guarantees that no write occurred. Late results are discarded, and a blocked worker exits when its in-flight operation returns.
- Specify an explicit Executor for completion handlers. Do not block the main thread with `get()` / `join()` or manually complete the returned Future.
- This API waits for volume access, not for all background media scans to finish. Existing candidate selection and ID creation behavior remain unchanged. Only `SUCCESS` confirms that an ID was obtained.

The instrumentation test for an existing ID requires full image access and an existing marker image. It is skipped if these conditions are not met. Run this test with permission granted and the access-denial test with permission revoked, and inspect the raw instrumentation status output for skipped tests. The test APK targets API 34 independently of the consuming app. Boot-cycle validation requires calling this native API during startup on a physical device; passing instrumentation tests on an already booted device is insufficient.

Build and test the library using JDK 17 and the Android SDK:

```bash
./android/gradlew -p android testReleaseUnitTest
./android/gradlew -p android :device-id-provider:syncUnityAar
```

The second command rebuilds the committed AAR at `Packages/com.styly.device-id-provider/Plugins/Android/styly-device-id-provider.aar`. The same release component can be installed into the local Maven repository with `:device-id-provider:publishReleasePublicationToMavenLocal`. Before publishing remotely, configure the destination repository in the Gradle publishing block.

### Windows and macOS

- The standalone provider stores the GUID in a text file under the user's application data directory.
    - Windows: `%LOCALAPPDATA%/Styly/Device-ID-Provider/device.id`
    - macOS: `~/Library/Application Support/Styly/Device-ID-Provider/device.id`
- In the Unity Editor, the GUID is stored in a subdirectory derived from a stable hash of `Application.dataPath`. This isolates device IDs by project directory, ParrelSync clone, and Multiplayer Play Mode virtual player instead of sharing one file across all Editor instances.
- If these special folders are unavailable (for example, in a restricted environment), the provider falls back to `Application.persistentDataPath`.
- Existing file contents are validated, and the GUID is regenerated if the file is missing or corrupt.

### Unsupported platforms

Calling `GetDeviceID()` on any other platform (such as iOS or WebGL) currently throws `PlatformNotSupportedException`.

## Package layout

```
Packages/com.styly.device-id-provider/
├── Plugins/Android/
│   └── styly-device-id-provider.aar       # Native Android implementation used by Unity
├── Runtime/
│   ├── DeviceIdProvider.cs                # Entry point that selects the implementation at runtime
│   ├── Providers/
│   │   ├── AndroidDeviceIdProvider.cs     # Thin Unity JNI wrapper for the Android AAR
│   │   ├── StandaloneDeviceIdProvider.cs  # File-based persistence for Windows/macOS
│   │   └── UnsupportedDeviceIdProvider.cs # Throws on other platforms
│   └── Internal/
│       └── AndroidBridge.cs               # Minimal Unity <-> Android JNI helpers
└── package.json                           # Package metadata
```

Native Android sources, tests, AAR build configuration, and Maven publishing configuration are in `android/device-id-provider/`.
