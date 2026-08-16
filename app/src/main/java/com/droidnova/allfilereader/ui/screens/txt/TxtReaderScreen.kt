package com.droidnova.allfilereader.ui.screens.txt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtReaderScreen(onBack: () -> Unit, viewModel: TxtReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.fileName ?: stringResource(R.string.txt_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }
        )
    }) { padding ->
        when (val content = state.content) {
            TxtReaderContent.Loading -> {
                val loading = stringResource(R.string.txt_loading)
                Box(Modifier.fillMaxSize().padding(padding).semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is TxtReaderContent.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                itemsIndexed(content.chunks, key = { index, _ -> index }) { _, chunk ->
                    Text(text = chunk, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), softWrap = true)
                }
                if (!content.endReached) item(key = "load-more") {
                    LaunchedEffect(content.chunks.size) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        if (content.isAppending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            TxtReaderContent.Empty -> TxtMessage(R.string.txt_empty, R.string.txt_empty_message, padding, onBack)
            TxtReaderContent.NotFound -> TxtMessage(R.string.txt_not_found, R.string.txt_not_found_message, padding, onBack, viewModel::retry)
            TxtReaderContent.AccessDenied -> TxtMessage(R.string.txt_access_removed, R.string.txt_access_removed_message, padding, onBack, requestAccess, R.string.allow_access)
            TxtReaderContent.UnsupportedEncoding -> TxtMessage(R.string.txt_cannot_open, R.string.txt_encoding_unsupported, padding, onBack)
            TxtReaderContent.Binary -> TxtMessage(R.string.txt_cannot_open, R.string.txt_binary_unsupported, padding, onBack)
            TxtReaderContent.TooLarge -> TxtMessage(R.string.txt_cannot_open, R.string.txt_too_large, padding, onBack)
            TxtReaderContent.ReadError -> TxtMessage(R.string.txt_cannot_open, R.string.txt_read_error, padding, onBack, viewModel::retry)
        }
    }
}

@Composable
private fun TxtMessage(title: Int, message: Int, padding: PaddingValues, onBack: () -> Unit, action: (() -> Unit)? = null, actionLabel: Int = R.string.try_again) {
    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(52.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            action?.let { Button(onClick = it) { Text(stringResource(actionLabel)) } }
        }
    }
}
