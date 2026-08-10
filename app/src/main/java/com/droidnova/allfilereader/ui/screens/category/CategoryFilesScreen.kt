package com.droidnova.allfilereader.ui.screens.category

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.data.permission.MediaPermissionType
import com.droidnova.allfilereader.ui.components.DocumentFileRow

@Composable
fun CategoryFilesScreen(onBack: () -> Unit, viewModel: CategoryFilesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requested = true
        viewModel.onPermissionResult(it)
    }
    CategoryFilesContent(
        state = state,
        showOpenSettings = state.requiredPermission?.let {
            requested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it.permission) &&
                ContextCompat.checkSelfPermission(context, it.permission) != PackageManager.PERMISSION_GRANTED
        } == true,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onAllowAccess = { state.requiredPermission?.let { launcher.launch(it.permission) } },
        onNotNow = viewModel::dismissPermissionPrompt,
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        },
        onFileClick = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryFilesContent(
    state: CategoryFilesUiState,
    showOpenSettings: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onAllowAccess: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onFileClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.category.titleRes())) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val permission = state.requiredPermission
            if (permission != null && !state.permissionPromptDismissed) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (permission.type == MediaPermissionType.Images) R.string.image_access_title else R.string.file_access_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(if (permission.type == MediaPermissionType.Images) R.string.image_access_message else R.string.file_access_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = if (showOpenSettings) onOpenSettings else onAllowAccess) { Text(stringResource(if (showOpenSettings) R.string.open_app_settings else R.string.allow_access)) }
                        TextButton(onClick = onNotNow) { Text(stringResource(R.string.not_now)) }
                    }
                }
            }
            when (val load = state.loadState) {
                CategoryLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is CategoryLoadState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                    items(load.documents, key = { it.uri }) { file -> DocumentFileRow(file, { onFileClick(file.id) }) }
                }
                CategoryLoadState.Empty -> Message(stringResource(state.category.emptyRes()), stringResource(R.string.category_empty_supporting_text), stringResource(R.string.refresh), onRefresh)
                CategoryLoadState.Error -> Message(stringResource(R.string.files_error_title), stringResource(R.string.files_error_supporting_text), stringResource(R.string.try_again), onRetry)
            }
        }
    }
}

@Composable
private fun Message(title: String, supporting: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.FolderOff, null, Modifier.size(52.dp)); Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun FileCategory.titleRes() = when (this) {
    FileCategory.All -> R.string.all_files; FileCategory.Pdf -> R.string.pdf_files
    FileCategory.Word -> R.string.word_files; FileCategory.Excel -> R.string.excel_files
    FileCategory.PowerPoint -> R.string.powerpoint_files; FileCategory.Text -> R.string.text_files
    FileCategory.Images -> R.string.images
}
private fun FileCategory.emptyRes() = when (this) {
    FileCategory.All -> R.string.no_files_found; FileCategory.Pdf -> R.string.no_pdf_files
    FileCategory.Word -> R.string.no_word_files; FileCategory.Excel -> R.string.no_excel_files
    FileCategory.PowerPoint -> R.string.no_powerpoint_files; FileCategory.Text -> R.string.no_text_files
    FileCategory.Images -> R.string.no_image_files
}
