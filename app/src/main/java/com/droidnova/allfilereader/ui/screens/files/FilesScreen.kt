package com.droidnova.allfilereader.ui.screens.files

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.droidnova.allfilereader.data.permission.RequiredMediaPermission
import com.droidnova.allfilereader.ui.components.DocumentFileRow

@Composable
fun FilesScreen(
    onChooseFolder: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequested = true
        viewModel.onPermissionResult(granted)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    FilesScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        showOpenSettings = uiState.requiredPermission?.let { permission ->
            permissionRequested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.permission) &&
                ContextCompat.checkSelfPermission(context, permission.permission) != PackageManager.PERMISSION_GRANTED
        } == true,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onAllowAccess = {
            uiState.requiredPermission?.let { permissionLauncher.launch(it.permission) }
        },
        onNotNow = viewModel::dismissPermissionPrompt,
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        },
        onChooseFolder = onChooseFolder,
        onFileClick = {},
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesScreenContent(
    uiState: FilesUiState,
    snackbarHostState: SnackbarHostState,
    showOpenSettings: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onAllowAccess: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseFolder: () -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.files)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TextButton(
                onClick = onChooseFolder,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(text = stringResource(R.string.choose_folder))
            }
            val permission = uiState.requiredPermission
            if (permission != null && !uiState.permissionPromptDismissed) {
                PermissionNotice(
                    permission = permission,
                    showOpenSettings = showOpenSettings,
                    onAllowAccess = onAllowAccess,
                    onNotNow = onNotNow,
                    onOpenSettings = onOpenSettings
                )
            }

            when (val loadState = uiState.loadState) {
                FilesLoadState.Loading -> CenteredContent {
                    CircularProgressIndicator()
                }
                is FilesLoadState.Content -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(loadState.documents, key = { it.uri }) { document ->
                        DocumentFileRow(
                            document = document,
                            onClick = { onFileClick(document.id) }
                        )
                    }
                }
                FilesLoadState.Empty -> FileMessage(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.no_files_found),
                    supportingText = stringResource(R.string.no_files_found_supporting_text),
                    actionLabel = stringResource(R.string.refresh),
                    onAction = onRefresh
                )
                FilesLoadState.Error -> FileMessage(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.files_error_title),
                    supportingText = stringResource(R.string.files_error_supporting_text),
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry
                )
            }
        }
    }
}

@Composable
private fun PermissionNotice(
    permission: RequiredMediaPermission,
    showOpenSettings: Boolean,
    onAllowAccess: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isImages = permission.type == MediaPermissionType.Images
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = stringResource(if (isImages) R.string.image_access_title else R.string.file_access_title),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = stringResource(if (isImages) R.string.image_access_message else R.string.file_access_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showOpenSettings) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_app_settings)) }
            } else {
                Button(onClick = onAllowAccess) { Text(stringResource(R.string.allow_access)) }
            }
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.not_now)) }
        }
    }
}

@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun FileMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supportingText: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    CenteredContent {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
