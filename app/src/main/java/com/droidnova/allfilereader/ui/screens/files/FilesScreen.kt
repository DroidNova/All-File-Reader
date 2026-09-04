package com.droidnova.allfilereader.ui.screens.files

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.ui.components.PagedDocumentList
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onDocumentClick: (DocumentFile) -> Unit, viewModel: FilesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val documents = viewModel.documents.collectAsLazyPagingItems()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(selectedFilter) { listState.scrollToItem(0) }
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val request = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Column { TopAppBar(title = { Text(stringResource(R.string.recent)) }); RecentFilterRow(selectedFilter, viewModel::selectFilter) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val emptyTitle = when (selectedFilter) {
                RecentDocumentFilter.All -> R.string.no_recent_files
                RecentDocumentFilter.Pdf -> R.string.no_recent_pdf_files
                RecentDocumentFilter.Word -> R.string.no_recent_word_files
                RecentDocumentFilter.Excel -> R.string.no_recent_excel_files
                RecentDocumentFilter.PowerPoint -> R.string.no_recent_powerpoint_files
                RecentDocumentFilter.Text -> R.string.no_recent_text_files
            }
            PagedDocumentList(documents, state.hasAccess, state.permissionPromptDismissed,
                emptyTitle, R.string.no_recent_files_supporting_text,
                viewModel::prepareRefresh, request, viewModel::dismissPermissionPrompt, onDocumentClick, listState)
        }
    }
}

@Composable
private fun RecentFilterRow(selected: RecentDocumentFilter, onSelected: (RecentDocumentFilter) -> Unit) {
    val labels = listOf(R.string.category_all, R.string.pdf, R.string.word, R.string.excel, R.string.ppt, R.string.txt)
    ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 0.dp, divider = {}) {
        RecentDocumentFilter.entries.forEachIndexed { index, filter ->
            Tab(selected = filter == selected, onClick = { onSelected(filter) }, text = { Text(stringResource(labels[index])) })
        }
    }
}
