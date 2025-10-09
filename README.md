# Device-ID-Provider

Unity package and Android library for cross-app device ID via shared JSON.

Quick start
- Build the Android AAR: see `Android/device-id-provider` (Gradle).
- Copy the resulting `.aar` into `Packages/com.styly.device-id-provider/Runtime/Plugins/Android/`.
- In Unity, call `STYLY.DeviceIdProvider.DeviceIdProviderUnity.GetDeviceID(...)`.

Notes
- API ≤ 32: Uses MediaStore (Downloads) with `RELATIVE_PATH="Download/Device-ID-Provider/"`.
- API ≥ 33: Uses SAF; first call will show a folder picker to persist access.
