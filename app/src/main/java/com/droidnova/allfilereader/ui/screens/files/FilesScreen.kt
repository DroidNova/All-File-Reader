package com.droidnova.allfilereader.ui.screens.files

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val request = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recent)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PagedDocumentList(documents, state.hasAccess, state.permissionPromptDismissed,
                R.string.no_recent_files, R.string.no_recent_files_supporting_text,
                viewModel::prepareRefresh, request, viewModel::dismissPermissionPrompt, onDocumentClick)
        }
    }
}
