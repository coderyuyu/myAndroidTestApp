# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.mdedit.ui.components.WysiwygEditorView$* { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
