# Keep the JavaScript bridge (reflection target from the WebView runtime).
-keepclassmembers,allowobfuscation class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the JS bridge classes themselves.
-keep class com.minibrowser.BrowserCore$* { *; }
-keep class com.minibrowser.download.**$* { *; }

# org.json is part of the platform; R8 already knows it, but be explicit.
-dontwarn org.json.**

# Download model is serialized manually (no reflection), but keep fields safe.
-keep class com.minibrowser.download.DownloadTask { *; }
