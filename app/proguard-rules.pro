# WebView invokes annotated methods by their Java names. Preserve only those exposed
# members; otherwise R8 could rename/remove DocxStatusBridge.update and DOCX status,
# page, and search updates would stop reaching the app.
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}
