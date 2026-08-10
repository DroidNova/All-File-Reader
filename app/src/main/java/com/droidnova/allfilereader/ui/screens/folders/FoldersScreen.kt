package com.droidnova.allfilereader.ui.screens.folders

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.SafEntry
import com.droidnova.allfilereader.ui.components.DocumentFileRow

@Composable
fun FoldersScreen(onNavigateHome: () -> Unit, viewModel: FoldersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.addFolder(it.toString()) }
    }
    val back = { if (!viewModel.navigateBack()) onNavigateHome() }
    BackHandler(onBack = back)
    FoldersContent(state, back, { picker.launch(null) }, viewModel::refresh, viewModel::open)
    if (state.persistenceFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPersistenceMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissPersistenceMessage) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.folder_access_not_saved_title)) },
            text = { Text(stringResource(R.string.folder_access_not_saved_message)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoldersContent(
    state: FoldersUiState,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onEntryClick: (SafEntry) -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.currentFolderName ?: stringResource(R.string.folders), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.choose_folder)) }
            }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val load = state.loadState) {
                FolderLoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is FolderLoadState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                    items(load.entries, key = SafEntry::uri) { entry ->
                        if (entry.isDirectory) FolderRow(entry, state.isShowingRoots, { onEntryClick(entry) })
                        else DocumentFileRow(entry.asDocument(), { onEntryClick(entry) })
                    }
                }
                FolderLoadState.Empty -> FolderMessage(
                    if (state.isShowingRoots) stringResource(R.string.no_folders_added) else stringResource(R.string.folder_is_empty),
                    if (state.isShowingRoots) stringResource(R.string.no_folders_supporting_text) else stringResource(R.string.folder_is_empty_supporting_text),
                    if (state.isShowingRoots) onChooseFolder else onRefresh,
                    if (state.isShowingRoots) stringResource(R.string.choose_folder) else stringResource(R.string.refresh)
                )
                FolderLoadState.Error -> FolderMessage(stringResource(R.string.folder_error_title), stringResource(R.string.folder_error_message), onRefresh, stringResource(R.string.try_again))
                FolderLoadState.PermissionRevoked -> FolderMessage(stringResource(R.string.folder_access_revoked_title), stringResource(R.string.folder_access_revoked_message), onChooseFolder, stringResource(R.string.choose_folder))
            }
        }
    }
}

@Composable
private fun FolderRow(entry: SafEntry, root: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) {
                Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (root) R.string.folder_access_granted else R.string.folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FolderMessage(title: String, message: String, action: () -> Unit, actionLabel: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FolderOff, null, Modifier.size(52.dp)); Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = action) { Text(actionLabel) }
    }
}

private fun SafEntry.asDocument(): DocumentFile {
    val extension = DocumentClassifier.extensionOf(displayName)
    return DocumentFile(id, displayName, uri, mimeType, extension, sizeBytes ?: -1, lastModifiedEpochMillis ?: 0,
        DocumentClassifier.classify(mimeType, extension), false)
}
