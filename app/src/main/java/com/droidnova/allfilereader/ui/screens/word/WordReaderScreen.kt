package com.droidnova.allfilereader.ui.screens.word

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject

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
    var webView by remember { mutableStateOf<WebView?>(null) }
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    BackHandler(searching) { searching = false; query = ""; webView?.evaluateJavascript("viewer.clearSearch()", null) }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)

    Scaffold(topBar = {
        if (searching) {
            SearchBar(
                query = query,
                position = searchPosition,
                total = searchTotal,
                onQuery = {
                    query = it
                    webView?.evaluateJavascript("viewer.search(${JSONObject.quote(it)})", null)
                },
                onPrevious = { webView?.evaluateJavascript("viewer.previous()", null) },
                onNext = { webView?.evaluateJavascript("viewer.next()", null) },
                onClose = { searching = false; query = ""; webView?.evaluateJavascript("viewer.clearSearch()", null) }
            )
        } else {
            TopAppBar(
                title = { Text(state.fileName ?: stringResource(R.string.word_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (state.content is WordReaderContent.Ready) {
                        Text(if (pages > 0) stringResource(R.string.word_page_indicator, page.coerceAtLeast(1), pages) else "", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { webView?.evaluateJavascript("viewer.fitWidth()", null) }) { Icon(Icons.Outlined.FitScreen, stringResource(R.string.word_fit_width)) }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.word_search)) }
                    }
                }
            )
        }
    }) { padding ->
        when (val content = state.content) {
            WordReaderContent.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is WordReaderContent.Ready -> DocxWebView(
                content = content,
                modifier = Modifier.fillMaxSize().padding(padding),
                onWebView = { webView = it },
                onSearch = { current, total -> searchPosition = current; searchTotal = total },
                onPage = { current, total -> page = current; pages = total }
            )
            WordReaderContent.LegacyDoc -> WordMessage(R.string.word_unsupported, R.string.word_legacy_doc_message, padding, onBack)
            WordReaderContent.Missing -> WordMessage(R.string.word_not_found, R.string.word_not_found_message, padding, onBack, viewModel::retry)
            WordReaderContent.AccessDenied -> WordMessage(R.string.word_access_removed, R.string.word_access_removed_message, padding, onBack, requestAccess, R.string.allow_access)
            WordReaderContent.SafetyLimit -> WordMessage(R.string.word_cannot_open, R.string.word_safety_message, padding, onBack)
            WordReaderContent.Failure -> WordMessage(R.string.word_cannot_open, R.string.word_corrupted_message, padding, onBack, viewModel::retry)
        }
    }
}

@Composable
private fun SearchBar(query: String, position: Int, total: Int, onQuery: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.word_search)) },
                singleLine = true,
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                trailingIcon = { Text(stringResource(R.string.word_search_indicator, position, total), style = MaterialTheme.typography.labelMedium) }
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.back)) } },
        actions = {
            IconButton(enabled = total > 0, onClick = onPrevious) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.word_previous_result)) }
            IconButton(enabled = total > 0, onClick = onNext) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.word_next_result)) }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DocxWebView(content: WordReaderContent.Ready, modifier: Modifier, onWebView: (WebView) -> Unit, onSearch: (Int, Int) -> Unit, onPage: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val latestSearch by rememberUpdatedState(onSearch)
    val latestPage by rememberUpdatedState(onPage)
    val bridge = remember(content.sessionId) { ViewerBridge({ a, b -> latestSearch(a, b) }, { a, b -> latestPage(a, b) }) }
    val loader = remember(content.sessionId) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/session/${content.sessionId}/") { path ->
                if (path == "document.docx") WebResourceResponse(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    null,
                    content.bytes.inputStream()
                ) else null
            }
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = {
            WebView(it).apply {
                setBackgroundColor(Color.rgb(238, 238, 238))
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    mediaPlaybackRequiresUserGesture = true
                    safeBrowsingEnabled = true
                }
                addJavascriptInterface(bridge, "AndroidViewer")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        loader.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), byteArrayOf().inputStream())
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                loadUrl("https://appassets.androidplatform.net/assets/docx_viewer/viewer.html?session=${content.sessionId}")
                onWebView(this)
            }
        },
        update = { onWebView(it) },
        onRelease = {
            it.stopLoading()
            it.removeJavascriptInterface("AndroidViewer")
            it.destroy()
        }
    )
}

private class ViewerBridge(private val search: (Int, Int) -> Unit, private val page: (Int, Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun searchState(current: Int, total: Int) { main.post { search(current, total) } }
    @JavascriptInterface fun pageState(current: Int, total: Int) { main.post { page(current, total) } }
}

@Composable
private fun WordMessage(title: Int, message: Int, padding: PaddingValues, onBack: () -> Unit, action: (() -> Unit)? = null, actionLabel: Int = R.string.try_again) {
    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(52.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }; action?.let { Button(onClick = it) { Text(stringResource(actionLabel)) } } }
    }
}
