package com.droidnova.allfilereader.ui.screens.pdf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.compose.AndroidFragment
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(onBack: () -> Unit, viewModel: PdfReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<PdfViewerFragment?>(null) }
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)
    val back = {
        val fragment = viewer
        if (fragment?.isTextSearchActive == true) fragment.isTextSearchActive = false else onBack()
    }
    BackHandler(onBack = back)
    val ready = state.document as? PdfDocumentState.Ready
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(ready?.fileName ?: stringResource(R.string.pdf_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (ready != null) IconButton(onClick = { viewer?.isTextSearchActive = true }) {
                    Icon(Icons.Default.Search, stringResource(R.string.pdf_search))
                }
            }
        )
    }) { padding ->
        when (val document = state.document) {
            PdfDocumentState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is PdfDocumentState.Ready -> AndroidFragment<PdfViewerFragment>(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) { fragment ->
                viewer = fragment
                try {
                    if (fragment.documentUri != document.uri) fragment.documentUri = document.uri
                } catch (_: UnsupportedOperationException) {
                    viewModel.viewerUnsupported()
                }
            }
            PdfDocumentState.NotFound -> PdfError(R.string.pdf_not_found, R.string.pdf_not_found_message, padding, onBack, viewModel::retry)
            PdfDocumentState.AccessDenied -> PdfError(R.string.pdf_access_removed, R.string.pdf_access_removed_message, padding, onBack, requestAccess)
            PdfDocumentState.Unsupported -> PdfError(R.string.pdf_unsupported, R.string.pdf_unsupported_message, padding, onBack, null)
        }
    }
}

@Composable
private fun PdfError(title: Int, message: Int, padding: PaddingValues, onBack: () -> Unit, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.BrokenImage, null, Modifier.size(52.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            }
        }
    }
}
