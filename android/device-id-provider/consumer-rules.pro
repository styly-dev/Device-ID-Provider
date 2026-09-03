# The public API is called from Unity through JNI.
-keep public class com.styly.deviceid.DeviceIdProvider { public *; }
-keep public class com.styly.deviceid.DeviceIdResult { public *; }
-keep public enum com.styly.deviceid.DeviceIdStatus { *; }
