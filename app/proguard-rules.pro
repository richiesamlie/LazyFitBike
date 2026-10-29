# Preserve WebView JavascriptInterface bridge methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.fitness.zwiftbike.MapJsBridge { *; }
-keep class com.fitness.zwiftbike.MainActivity { *; }
-dontwarn org.slf4j.**
