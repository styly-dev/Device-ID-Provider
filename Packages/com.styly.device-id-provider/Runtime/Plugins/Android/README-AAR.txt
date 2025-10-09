Place the built AAR here as `com.styly.deviceidprovider.aar` (or keep the Gradle module path in a UPM scoped registry). Unity will include any `.aar` files under this folder into the Android build.

Build steps (from Android/device-id-provider):
1. `./gradlew assembleRelease`
2. Copy `Android/device-id-provider/build/outputs/aar/device-id-provider-release.aar` to this folder and rename if desired.

