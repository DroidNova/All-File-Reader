package com.droidnova.allfilereader.ui.screens.category

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilesScreen(onBack: () -> Unit, onDocumentClick: (DocumentFile) -> Unit, viewModel: CategoryFilesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val documents = viewModel.documents.collectAsLazyPagingItems()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle(emptySet())
    val favoriteUpdates by viewModel.favoriteUpdates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val favoriteError = stringResource(R.string.favorites_update_failed)
    LaunchedEffect(Unit) { viewModel.favoriteErrors.collect { snackbar.showSnackbar(favoriteError) } }
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val request = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text(stringResource(state.category.title())) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PagedDocumentList(documents, state.hasAccess, state.permissionPromptDismissed,
                state.category.empty(), R.string.category_empty_supporting_text,
                viewModel::prepareRefresh, request, viewModel::dismissPermissionPrompt, onDocumentClick,
                favoriteIds, favoriteUpdates, viewModel::toggleFavorite)
        }
    }
}

private fun FileCategory.title() = when (this) { FileCategory.All->R.string.all_files;FileCategory.Pdf->R.string.pdf_files;FileCategory.Word->R.string.word_files;FileCategory.Excel->R.string.excel_files;FileCategory.PowerPoint->R.string.ppt_files;FileCategory.Text->R.string.txt_files }
private fun FileCategory.empty() = when (this) { FileCategory.All->R.string.no_files_found;FileCategory.Pdf->R.string.no_pdf_files;FileCategory.Word->R.string.no_word_files;FileCategory.Excel->R.string.no_excel_files;FileCategory.PowerPoint->R.string.no_ppt_files;FileCategory.Text->R.string.no_txt_files }
