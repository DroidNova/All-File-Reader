package com.droidnova.allfilereader.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.ui.screens.home.PermissionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedDocumentList(
    documents: LazyPagingItems<DocumentFile>,
    hasAccess: Boolean,
    permissionPromptDismissed: Boolean,
    @StringRes emptyTitle: Int,
    @StringRes emptyText: Int,
    onRefreshRequested: () -> Unit,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    onDocumentClick: (DocumentFile) -> Unit
) {
    val refreshing = documents.loadState.refresh is LoadState.Loading && documents.itemCount > 0
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { onRefreshRequested(); documents.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasAccess) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                if (!permissionPromptDismissed) item { PermissionCard(onAllow, onNotNow) }
            }
            return@PullToRefreshBox
        }
        when {
            documents.loadState.refresh is LoadState.Loading && documents.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            documents.loadState.refresh is LoadState.Error && documents.itemCount == 0 ->
                PagedMessage(R.string.files_error_title, R.string.files_error_supporting_text, documents::retry)
            documents.loadState.refresh is LoadState.NotLoading && documents.itemCount == 0 ->
                PagedMessage(emptyTitle, emptyText)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(documents.itemCount, key = documents.itemKey(DocumentFile::id)) { index ->
                    documents[index]?.let { document -> DocumentFileRow(document) { onDocumentClick(document) } }
                }
                when (documents.loadState.append) {
                    LoadState.Loading -> item(key = "append-loading") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    is LoadState.Error -> item(key = "append-error") {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.files_append_error))
                            TextButton(onClick = documents::retry) { Text(stringResource(R.string.try_again)) }
                        }
                    }
                    is LoadState.NotLoading -> Unit
                }
            }
        }
    }
}

@Composable
private fun PagedMessage(@StringRes title: Int, @StringRes text: Int, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.FolderOff, null, Modifier.size(52.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(text), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retry?.let { TextButton(onClick = it) { Text(stringResource(R.string.try_again)) } }
    }
}
