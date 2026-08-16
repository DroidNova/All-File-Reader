package com.droidnova.allfilereader.ui.screens.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.*
import com.droidnova.allfilereader.ui.components.DocumentFileRow
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import com.droidnova.allfilereader.ui.screens.home.PermissionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onNavigateHome: () -> Unit, onDocumentClick: (DocumentFile) -> Unit, viewModel: FoldersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entries = viewModel.entries.collectAsLazyPagingItems()
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val back = { if (!viewModel.navigateBack()) onNavigateHome() }
    BackHandler(onBack = back)
    val request = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(topBar = { TopAppBar(title = { Text(state.currentFolderName ?: stringResource(R.string.directories), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        val refreshing = entries.loadState.refresh is LoadState.Loading && entries.itemCount > 0
        PullToRefreshBox(refreshing, entries::refresh, Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.hasAccess -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) { item { PermissionCard(request, {}) } }
                entries.loadState.refresh is LoadState.Loading && entries.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                entries.loadState.refresh is LoadState.Error && entries.itemCount == 0 -> Message(R.string.folder_error_title, R.string.directory_access_denied, entries::retry)
                entries.loadState.refresh is LoadState.NotLoading && entries.itemCount == 0 -> Message(R.string.folder_is_empty, R.string.folder_is_empty_supporting_text)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.itemCount, key = entries.itemKey(SafEntry::id)) { index -> entries[index]?.let { entry ->
                        if (entry.isDirectory) FolderRow(entry, state.isShowingRoots) { viewModel.open(entry) }
                        else { val document = entry.asDocument(); DocumentFileRow(document) { onDocumentClick(document) } }
                    } }
                    when (entries.loadState.append) {
                        LoadState.Loading -> item("append-loading") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
                        is LoadState.Error -> item("append-error") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TextButton(onClick = entries::retry) { Text(stringResource(R.string.try_again)) } } }
                        is LoadState.NotLoading -> Unit
                    }
                }
            }
        }
    }
}

@Composable private fun FolderRow(entry: SafEntry, root: Boolean, onClick: () -> Unit) { Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, Modifier.size(40.dp));Spacer(Modifier.width(16.dp));Column { Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis);Text(stringResource(if(root) R.string.storage_root else R.string.folder), color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable private fun Message(title: Int, text: Int, retry: (() -> Unit)? = null) { Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.FolderOff, null, Modifier.size(52.dp));Text(stringResource(title), style = MaterialTheme.typography.titleMedium);Text(stringResource(text));retry?.let { TextButton(onClick = it) { Text(stringResource(R.string.try_again)) } } } }
private fun SafEntry.asDocument(): DocumentFile { val ext=DocumentClassifier.extensionOf(displayName);return DocumentFile(DocumentIds.fromStorageLocation(uri),displayName,java.io.File(uri).toURI().toString(),mimeType,ext,sizeBytes?:-1,lastModifiedEpochMillis?:0,DocumentClassifier.classify(mimeType,ext),false) }
