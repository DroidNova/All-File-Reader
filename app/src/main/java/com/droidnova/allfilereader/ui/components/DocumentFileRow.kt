package com.droidnova.allfilereader.ui.components

import android.content.Context
import android.text.format.Formatter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentFileRow(
    document: DocumentFile,
    onClick: () -> Unit,
    isFavorite: Boolean = document.isBookmarked,
    favoriteEnabled: Boolean = true,
    onFavoriteToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlightedName: AnnotatedString? = null
) {
    val type = document.extension?.uppercase(Locale.getDefault())
        ?: stringResource(document.category.labelResId())
    val size = formatFileSize(LocalContext.current, document.sizeBytes)
        ?: stringResource(R.string.unknown_file_info)
    val date = formatLastModified(document.lastModifiedEpochMillis)
        ?: stringResource(R.string.unknown_file_info)

    Surface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = document.category.icon(),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = highlightedName ?: AnnotatedString(document.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.file_metadata, type, size, date),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            onFavoriteToggle?.let { toggle ->
                val action = stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites)
                IconToggleButton(
                    checked = isFavorite,
                    onCheckedChange = { toggle() },
                    enabled = favoriteEnabled,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = action,
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun formatFileSize(context: Context, sizeBytes: Long): String? {
    if (sizeBytes < 0) return null
    return Formatter.formatShortFileSize(context, sizeBytes)
}

fun formatLastModified(epochMillis: Long): String? = epochMillis
    .takeIf { it > 0 }
    ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

private fun DocumentCategory.icon(): ImageVector = when (this) {
    DocumentCategory.Pdf -> Icons.Default.PictureAsPdf
    DocumentCategory.Word -> Icons.Default.Description
    DocumentCategory.Excel -> Icons.Default.TableChart
    DocumentCategory.PowerPoint -> Icons.Default.Slideshow
    DocumentCategory.Text -> Icons.Default.TextSnippet
    DocumentCategory.Folder -> Icons.Default.Folder
    DocumentCategory.Other -> Icons.Default.InsertDriveFile
}

private fun DocumentCategory.labelResId(): Int = when (this) {
    DocumentCategory.Pdf -> R.string.document_type_pdf
    DocumentCategory.Word -> R.string.document_type_word
    DocumentCategory.Excel -> R.string.document_type_excel
    DocumentCategory.PowerPoint -> R.string.document_type_powerpoint
    DocumentCategory.Text -> R.string.document_type_text
    DocumentCategory.Folder -> R.string.document_type_folder
    DocumentCategory.Other -> R.string.document_type_other
}
