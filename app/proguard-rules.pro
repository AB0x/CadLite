# Keep the JS bridge used by the DWG converter
-keepclassmembers class com.cadlite.viewer.DwgConverter$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
