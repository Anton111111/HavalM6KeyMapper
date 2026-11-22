# Keep enum member names stable because rules are persisted as action.name strings.
-keepclassmembernames enum com.anton111111.haval.m6.keymapper.data.KeyAction { *; }

# Strip all android.util.Log calls from release bytecode.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep MCU bridge API stable for JNI and callback dispatch in release builds.
-keep class com.adayo.mcucommproxy.McuCommManager { *; }
-keep class com.adayo.mcucommproxy.IMcuCommCallback { *; }
-keepclassmembers class * implements com.adayo.mcucommproxy.IMcuCommCallback {
    void mcuCommCallback(byte[], char);
}
