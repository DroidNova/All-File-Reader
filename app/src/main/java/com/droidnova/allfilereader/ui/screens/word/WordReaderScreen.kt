package com.droidnova.allfilereader.ui.screens.word

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
private const val RENDER_TIMEOUT_MS = 35_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordReaderScreen(onBack: () -> Unit, viewModel: WordReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchPosition by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var rendered by remember { mutableStateOf(false) }
    var renderFailed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    BackHandler(searching) { searching = false; query = ""; webView?.clearMatches() }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)
    LaunchedEffect(state.content) {
        if (state.content !is WordReaderContent.Ready) {
            renderFailed = false; rendered = false; searching = false; query = ""
            page = 0; pages = 0; searchPosition = 0; searchTotal = 0
        }
    }

    LaunchedEffect(query, searching, rendered, webView) {
        delay(250)
        val view = webView ?: return@LaunchedEffect
        if (!searching || !rendered || query.isBlank()) {
            view.clearMatches(); searchPosition = 0; searchTotal = 0
        } else {
            view.findAllAsync(query)
        }
    }

    Scaffold(topBar = {
        if (searching) SearchBar(query, searchPosition, searchTotal, { query = it },
            { webView?.findNext(false) }, { webView?.findNext(true) }) {
            searching = false; query = ""; webView?.clearMatches()
        } else TopAppBar(
            title = { Text(state.fileName ?: stringResource(R.string.word_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (state.content is WordReaderContent.Ready && rendered && !renderFailed) {
                    if (pages > 0) Text(stringResource(R.string.word_page_indicator, page, pages), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { webView?.evaluateJavascript("fitWidth()", null) }) { Icon(Icons.Outlined.FitScreen, stringResource(R.string.word_fit_width)) }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.word_search)) }
                }
            }
        )
    }) { padding ->
        when (val content = state.content) {
            WordReaderContent.Loading -> Loading(padding)
            is WordReaderContent.Ready -> if (renderFailed) {
                WordMessage(R.string.word_cannot_open, R.string.word_read_error, padding, onBack, viewModel::retry)
            } else DocxWebView(content, Modifier.fillMaxSize().padding(padding),
                onWebView = { webView = it },
                onRendered = { rendered = true },
                onPage = { current, total -> page = current; pages = total },
                onSearch = { current, total -> searchPosition = current; searchTotal = total },
                onFailure = { renderFailed = true; rendered = false })
            WordReaderContent.LegacyDoc -> WordMessage(R.string.word_unsupported, R.string.word_legacy_doc_message, padding, onBack)
            WordReaderContent.Unsupported -> WordMessage(R.string.word_unsupported, R.string.word_encrypted_message, padding, onBack)
            WordReaderContent.Missing -> WordMessage(R.string.word_not_found, R.string.word_not_found_message, padding, onBack, viewModel::retry)
            WordReaderContent.AccessDenied -> WordMessage(R.string.word_access_removed, R.string.word_access_removed_message, padding, onBack, requestAccess, R.string.allow_access)
            WordReaderContent.SafetyLimit -> WordMessage(R.string.word_cannot_open, R.string.word_safety_message, padding, onBack)
            WordReaderContent.Failure -> WordMessage(R.string.word_cannot_open, R.string.word_corrupted_message, padding, onBack, viewModel::retry)
        }
    }
}

@Composable private fun Loading(padding: PaddingValues) = Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun SearchBar(query: String, position: Int, total: Int, onQuery: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    TopAppBar(title = { TextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.word_search)) }, singleLine = true,
        keyboardActions = KeyboardActions(onSearch = { onNext() }), trailingIcon = { Text(stringResource(R.string.word_search_indicator, position, total), style = MaterialTheme.typography.labelMedium) }) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.back)) } }, actions = {
            IconButton(enabled = total > 0, onClick = onPrevious) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.word_previous_result)) }
            IconButton(enabled = total > 0, onClick = onNext) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.word_next_result)) }
        })
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DocxWebView(content: WordReaderContent.Ready, modifier: Modifier, onWebView: (WebView?) -> Unit, onRendered: () -> Unit, onPage: (Int, Int) -> Unit, onSearch: (Int, Int) -> Unit, onFailure: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentRendered by rememberUpdatedState(onRendered); val currentPage by rememberUpdatedState(onPage)
    val currentSearch by rememberUpdatedState(onSearch); val currentFailure by rememberUpdatedState(onFailure)
    val active = remember(content.sessionId) { AtomicBoolean(true) }
    val loader = remember(content.sessionId, content.file) {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/session/${content.sessionId}/") { path ->
                if (path == "document.docx" && content.file.isFile) WebResourceResponse(DOCX_MIME, null, content.file.inputStream()) else null
            }.build()
    }
    AndroidView(modifier = modifier, factory = {
        val main = Handler(Looper.getMainLooper()); val started = android.os.SystemClock.uptimeMillis()
        WebView(it).apply {
            setBackgroundColor(Color.rgb(238, 238, 238)); WebView.setWebContentsDebuggingEnabled(false)
            settings.apply {
                javaScriptEnabled = true; blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
                allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
                domStorageEnabled = false; databaseEnabled = false; setGeolocationEnabled(false)
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; builtInZoomControls = true
                displayZoomControls = false; setSupportZoom(true); mediaPlaybackRequiresUserGesture = true; safeBrowsingEnabled = true
            }
            setDownloadListener { _, _, _, _, _ -> }
            setFindListener { active, count, done -> if (done) currentSearch(if (count == 0) 0 else active + 1, count) }
            fun poll() {
                if (!active.get()) return
                if (android.os.SystemClock.uptimeMillis() - started > RENDER_TIMEOUT_MS) { currentFailure(); stopLoading(); return }
                evaluateJavascript("viewerState && viewerState()") { encoded ->
                    runCatching {
                        val json = JSONObject(JSONArray("[$encoded]").getString(0))
                        when (json.getString("status")) {
                            "ready" -> { currentRendered(); currentPage(json.getInt("page"), json.getInt("pages")); main.postDelayed({ poll() }, 500) }
                            "error" -> currentFailure()
                            else -> main.postDelayed({ poll() }, 250)
                        }
                    }.onFailure { main.postDelayed({ poll() }, 250) }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.scheme != "https" || request.url.host != "appassets.androidplatform.net") return blocked()
                    return loader.shouldInterceptRequest(request.url) ?: blocked()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun onPageFinished(view: WebView, url: String) { if (Uri.parse(url).host == "appassets.androidplatform.net") poll() else currentFailure() }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { view.destroy(); currentFailure(); return true }
            }
            loadUrl("$LOCAL_ORIGIN/assets/docx_viewer/viewer.html?session=${content.sessionId}"); onWebView(this)
        }
    }, update = { onWebView(it) }, onRelease = {
        active.set(false)
        it.setFindListener(null); it.clearMatches(); it.stopLoading(); it.loadUrl("about:blank"); it.clearHistory(); it.removeAllViews(); it.destroy(); onWebView(null)
    })
}

private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), byteArrayOf().inputStream())
private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

@Composable
private fun WordMessage(title: Int, message: Int, padding: PaddingValues, onBack: () -> Unit, action: (() -> Unit)? = null, actionLabel: Int = R.string.try_again) {
    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(52.dp)); Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }; action?.let { Button(onClick = it) { Text(stringResource(actionLabel)) } } }
    }
}
