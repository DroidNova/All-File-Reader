package com.droidnova.allfilereader.ui.screens.powerpoint

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerPointReaderScreen(onBack: () -> Unit, vm: PowerPointReaderViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var web by remember { mutableStateOf<WebView?>(null) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var slide by remember { mutableIntStateOf(0) }
    var slides by remember { mutableIntStateOf(0) }
    var preparingLegacy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val access = rememberStorageAccessRequest(vm::onResume)
    val context = LocalContext.current

    if (state.phase == PptxPhase.LegacyValid) AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Legacy PowerPoint presentation") },
        text = { Text("This presentation uses the older PowerPoint format. You can open it with a compatible app installed on your device.") },
        confirmButton = { TextButton(enabled = !preparingLegacy, onClick = {
            if (!preparingLegacy) { preparingLegacy = true; scope.launch { try { vm.prepareLegacyShare()?.let { vm.legacyOpenResult(LegacyPptExternalOpener.open(context, it)) } } finally { preparingLegacy = false } } }
        }) { Text("Open with another app") } },
        dismissButton = { TextButton(onClick = onBack) { Text("Cancel") } }
    )
    if (state.phase == PptxPhase.LegacyNoCompatibleApp) AlertDialog(
        onDismissRequest = onBack,
        title = { Text("No compatible app found") },
        text = { Text("Install an app that supports older PowerPoint presentations, or convert this file to PPTX on a trusted computer.") },
        confirmButton = { TextButton(onClick = onBack) { Text("OK") } }
    )
    BackHandler(search) { search = false; query = ""; web?.evaluateJavascript("pptxControl.clear()", null) }
    LaunchedEffect(query, search, state.phase) {
        if (search && state.phase == PptxPhase.Ready) {
            kotlinx.coroutines.delay(250)
            web?.evaluateJavascript("pptxControl.search(${JSONObject.quote(query)})", null)
        }
    }
    Scaffold(
        Modifier.fillMaxSize(),
        topBar = {
            if (search) TopAppBar(
                title = { TextField(query, { query = it }, singleLine = true, placeholder = { Text("Search slides") }, supportingText = { if (query.isNotEmpty()) Text(if (total == 0) "No matches" else "$current of $total") }) },
                navigationIcon = { IconButton(onClick = { search = false; query = ""; web?.evaluateJavascript("pptxControl.clear()", null) }) { Icon(Icons.Default.Close, "Close search") } },
                actions = {
                    IconButton(enabled = total > 0, onClick = { web?.evaluateJavascript("pptxControl.previous()", null) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
                    IconButton(enabled = total > 0, onClick = { web?.evaluateJavascript("pptxControl.next()", null) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
                }
            ) else TopAppBar(
                title = { Text(state.fileName ?: "PowerPoint Reader", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { if (state.phase == PptxPhase.Ready) {
                    Text("$slide/$slides", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { web?.evaluateJavascript("pptxControl.fit()", null) }) { Icon(Icons.Outlined.FitScreen, "Fit") }
                    IconButton(onClick = { search = true }) { Icon(Icons.Default.Search, "Search") }
                } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val ready = state.ready
            if (ready != null && state.phase in setOf(PptxPhase.Preparing, PptxPhase.Rendering, PptxPhase.Ready)) {
                PptxWeb(ready, Modifier.fillMaxWidth().weight(1f), { web = it }, { phase, sl, ss, c, t ->
                    slide = sl; slides = ss; current = c; total = t; vm.viewerPhase(ready.token, phase)
                }, { reason -> vm.releaseReader(ready.token, reason) })
            } else if (state.phase in setOf(PptxPhase.Resolving, PptxPhase.Copying, PptxPhase.Validating)) {
                LoadingMessage(state.phase)
            } else if (state.phase in setOf(PptxPhase.LegacyValid, PptxPhase.LegacyNoCompatibleApp)) Box(Modifier.fillMaxSize())
            else ErrorMessage(state.phase, onBack, if (state.phase == PptxPhase.PermissionDenied) access else vm::retry)
        }
    }
}

@Composable private fun LoadingMessage(phase: PptxPhase) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(when (phase) { PptxPhase.Copying -> "Preparing presentation…"; PptxPhase.Validating -> "Checking presentation…"; else -> "Opening presentation…" })
    }
}

private fun parse(raw: String, callback: (Int, Int, Int, Int) -> Unit) = runCatching {
    val value = JSONObject(JSONArray("[$raw]").getString(0))
    val count = value.optInt("matchCount")
    val index = value.optInt("activeMatchIndex", -1)
    callback(if (index >= 0) index + 1 else 0, count, value.optInt("slide", 1), value.optInt("slides"))
}

@Composable private fun ErrorMessage(phase: PptxPhase, back: () -> Unit, retry: () -> Unit) {
    val message = when (phase) {
        PptxPhase.LegacyInvalid -> "This is not a valid legacy PowerPoint file."
        PptxPhase.LegacyAccessDenied, PptxPhase.PermissionDenied, PptxPhase.Missing -> "The presentation could not be accessed."
        PptxPhase.LegacyPreparationFailed -> "The presentation could not be prepared for opening."
        PptxPhase.MacroUnsupported -> "This PowerPoint format is unsupported. Macros are never executed."
        PptxPhase.Encrypted -> "Password-protected presentations are not supported yet."
        PptxPhase.InvalidContainer -> "This is not a valid PPTX presentation."
        PptxPhase.MissingParts -> "This presentation is damaged or incomplete."
        PptxPhase.TooLargeForDevice -> "This presentation is too large to open safely on this device."
        PptxPhase.UnsafeArchive -> "This presentation could not be opened safely."
        PptxPhase.RendererStalled -> "The presentation stopped responding while it was being displayed."
        PptxPhase.InsufficientMemory -> "There isn’t enough memory available to open this presentation."
        PptxPhase.RendererAssetFailure, PptxPhase.RendererFailure -> "The presentation could not be displayed."
        else -> "The presentation could not be opened."
    }
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(message)
        Row { TextButton(onClick = back) { Text("Back") }; Button(onClick = retry) { Text("Try again") } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun PptxWeb(
    ready: PptxReady,
    modifier: Modifier,
    onWeb: (WebView?) -> Unit,
    onPhase: (PptxPhase, Int, Int, Int, Int) -> Unit,
    onRelease: (String) -> Unit
) {
    val context = LocalContext.current
    val currentPhase by rememberUpdatedState(onPhase)
    val active = remember(ready.token) { AtomicBoolean(true) }
    val loader = remember(ready.token) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/pptx_viewer/") { path ->
                if (path in VIEWER_ASSETS) runCatching { WebResourceResponse(contentType(path), "UTF-8", context.assets.open("pptx_viewer/$path")) }.getOrNull() else null
            }
            .addPathHandler("/presentation/${ready.token}/") { path ->
                if (path == "document.pptx" && ready.file.isFile) runCatching { WebResourceResponse(PowerPointReaderViewModel.PPTX_MIME, null, ready.file.inputStream()) }.getOrNull() else null
            }.build()
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext -> WebView(viewContext).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.rgb(231, 233, 236))
            WebView.setWebContentsDebuggingEnabled(false)
            settings.apply {
                javaScriptEnabled = true; javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false; allowContentAccess = false; allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
                setSupportMultipleWindows(false); mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; blockNetworkLoads = true
                databaseEnabled = false; domStorageEnabled = false; setGeolocationEnabled(false); saveFormData = false
                cacheMode = WebSettings.LOAD_NO_CACHE; builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true); safeBrowsingEnabled = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean { if (BuildConfig.DEBUG) android.util.Log.d(PowerPointReaderViewModel.TAG, "rendererConsole=${message.messageLevel()}"); return true }
                override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
            }
            setDownloadListener { _, _, _, _, _ -> }
            val handler = Handler(Looper.getMainLooper())
            val watchdog = RendererProgressWatchdog(ready.stallMillis, SystemClock.uptimeMillis())
            var rendered = false
            fun poll() {
                if (!active.get()) return
                if (!rendered && watchdog.isStalled(SystemClock.uptimeMillis())) {
                    active.set(false); currentPhase(PptxPhase.RendererStalled, 0, 0, 0, 0); stopLoading(); return
                }
                evaluateJavascript("pptxControl.status()") { raw ->
                    if (!active.get()) return@evaluateJavascript
                    runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.onSuccess { value ->
                        val stage = value.optString("stage")
                        watchdog.observe(stage, value.optLong("progress", -1), SystemClock.uptimeMillis())
                        when (value.optString("status")) {
                            "ready" -> { rendered = true; watchdog.stop(); parse(raw) { c, t, sl, ss -> currentPhase(PptxPhase.Ready, sl.coerceAtLeast(1), ss, c, t) }; handler.postDelayed({ poll() }, 500) }
                            "error" -> { active.set(false); watchdog.stop(); currentPhase(PptxPhase.RendererFailure, 0, 0, 0, 0) }
                            else -> handler.postDelayed({ poll() }, 500)
                        }
                    }.onFailure { handler.postDelayed({ poll() }, 500) }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (request.url.scheme == "https" && request.url.host == ORIGIN_HOST) loader.shouldInterceptRequest(request.url) ?: blocked() else blocked()
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) currentPhase(PptxPhase.RendererAssetFailure, 0, 0, 0, 0) }
                override fun onPageFinished(view: WebView, url: String) { currentPhase(PptxPhase.Rendering, 0, 0, 0, 0); watchdog.observe("viewer_loaded", 0, SystemClock.uptimeMillis()); poll() }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { active.set(false); watchdog.stop(); currentPhase(if (detail.didCrash()) PptxPhase.RendererFailure else PptxPhase.InsufficientMemory, 0, 0, 0, 0); return true }
            }
            loadUrl("https://$ORIGIN_HOST/assets/pptx_viewer/viewer.html?session=${ready.token}")
            onWeb(this)
        } },
        update = { onWeb(it) },
        onRelease = { view ->
            if (active.getAndSet(false)) view.evaluateJavascript("pptxControl.destroy()", null)
            view.stopLoading(); view.onPause(); view.removeJavascriptInterface(BRIDGE_NAME); view.loadUrl("about:blank")
            view.clearHistory(); (view.parent as? android.view.ViewGroup)?.removeView(view); view.removeAllViews(); view.destroy()
            onWeb(null); onRelease("WEBVIEW_RELEASED")
        }
    )
}

private fun contentType(path: String) = when { path.endsWith(".js") -> "text/javascript"; path.endsWith(".css") -> "text/css"; else -> "text/html" }
private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
private const val ORIGIN_HOST = "appassets.androidplatform.net"
private const val BRIDGE_NAME = "PptxNative"
private val VIEWER_ASSETS = setOf("viewer.html", "viewer.css", "viewer.js", "viewer-state.js", "aiden0z-pptx-renderer.browser.es.js")
