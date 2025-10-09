# Android Device ID Provider (AAR)

Build
- Requires Android SDK with build tools; Java 17 recommended.
- From this folder run:
  - macOS/Linux: `./gradlew assembleRelease`
  - Windows: `gradlew.bat assembleRelease`

Outputs
- `build/outputs/aar/device-id-provider-release.aar`

Unity integration
- Copy the AAR into `Packages/com.styly.device-id-provider/Runtime/Plugins/Android/`.
- In Unity C#, call `STYLY.DeviceIdProvider.DeviceIdProviderUnity.GetDeviceID(...)`.

Notes
- API ≤ 32 uses MediaStore (Downloads). First-time may show READ_EXTERNAL_STORAGE permission dialog.
- API ≥ 33 uses SAF. First-time shows folder picker and persists URI permission.

