package com.droidnova.allfilereader.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidnova.allfilereader.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MandatoryStoragePermissionSheet(requesting: Boolean, onAllow: () -> Unit) {
    val title = stringResource(R.string.storage_permission_title)
    val allowLabel = stringResource(R.string.allow_access)
    val activity = LocalContext.current.findActivity()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    BackHandler { activity?.moveTaskToBack(true) }
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier.semantics { paneTitle = title }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.storage_permission_description), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.storage_permission_privacy), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAllow,
                enabled = !requesting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = allowLabel
                }
            ) { Text(stringResource(R.string.allow_access)) }
            Text(
                stringResource(R.string.storage_permission_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
