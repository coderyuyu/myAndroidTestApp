# Proguard rules for GPX Editor
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
