# Keep WebView-related classes (no JS interfaces are used)
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
