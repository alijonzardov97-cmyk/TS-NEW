# Strip all logging from release builds so nothing sensitive can reach logcat.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
# UniFFI / JNA need their reflection targets.
-keep class com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.*
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.ts.messenger.** { kotlinx.serialization.KSerializer serializer(...); }
# WebRTC is reached from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
