package com.droidnova.allfilereader.ui.screens.word

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.*
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordReaderScreen(onBack: () -> Unit, viewModel: WordReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)
    Scaffold(topBar = { TopAppBar(
        title = { Text(state.fileName ?: stringResource(R.string.word_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }
    ) }) { padding ->
        when (val content = state.content) {
            WordReaderContent.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is WordReaderContent.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content.batches.forEach { batch -> items(batch, key = WordBlock::id, contentType = { it::class }) { block -> WordBlockItem(block, content.images) } }
                if (content.isParsing) item(key = "parsing") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
            }
            WordReaderContent.Empty -> WordMessage(R.string.word_empty, R.string.word_empty_message, padding, onBack)
            WordReaderContent.LegacyDoc -> WordMessage(R.string.word_unsupported, R.string.word_legacy_doc_message, padding, onBack)
            WordReaderContent.Encrypted -> WordMessage(R.string.word_unsupported, R.string.word_encrypted_message, padding, onBack)
            WordReaderContent.Corrupted -> WordMessage(R.string.word_cannot_open, R.string.word_corrupted_message, padding, onBack, viewModel::retry)
            WordReaderContent.Missing -> WordMessage(R.string.word_not_found, R.string.word_not_found_message, padding, onBack, viewModel::retry)
            WordReaderContent.AccessDenied -> WordMessage(R.string.word_access_removed, R.string.word_access_removed_message, padding, onBack, requestAccess, R.string.allow_access)
            WordReaderContent.SafetyLimit -> WordMessage(R.string.word_cannot_open, R.string.word_safety_message, padding, onBack)
            WordReaderContent.Failure -> WordMessage(R.string.word_cannot_open, R.string.word_read_error, padding, onBack, viewModel::retry)
        }
    }
}

@Composable
private fun WordBlockItem(block: WordBlock, images: Map<String, android.graphics.Bitmap>) {
    when (block) {
        is WordBlock.Paragraph -> Text(wordText(block.runs), style = MaterialTheme.typography.bodyLarge, textAlign = block.alignment.textAlign(), modifier = Modifier.padding(start = (block.indentLevel * 16).dp))
        is WordBlock.Heading -> Text(wordText(block.runs), style = when (block.level) { 1 -> MaterialTheme.typography.headlineMedium;2 -> MaterialTheme.typography.headlineSmall;else -> MaterialTheme.typography.titleLarge }, textAlign = block.alignment.textAlign(), modifier = Modifier.semantics { heading() })
        is WordBlock.ListItem -> Row(Modifier.padding(start = (block.level.coerceIn(0, 8) * 18).dp)) { Text(block.marker, Modifier.width(32.dp));Text(wordText(block.runs), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)) }
        is WordBlock.Table -> WordTable(block.rows)
        is WordBlock.Image -> {
            val bitmap = images[block.relationshipId]
            if (bitmap == null) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.HideImage, null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.word_image_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else Image(bitmap.asImageBitmap(), block.description, Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun WordTable(rows: List<List<String>>) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Box(
                        Modifier
                            .widthIn(min = 120.dp, max = 280.dp)
                            .border(0.5.dp, border)
                            .padding(8.dp)
                    ) {
                        Text(cell.ifEmpty { " " }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun wordText(runs: List<WordRun>): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val decorations=buildList { if(run.underline)add(TextDecoration.Underline);if(run.strike)add(TextDecoration.LineThrough) }
        val style=SpanStyle(fontWeight=if(run.bold)FontWeight.Bold else null,fontStyle=if(run.italic)FontStyle.Italic else null,
            textDecoration=decorations.takeIf{it.isNotEmpty()}?.let { TextDecoration.combine(it) },fontSize=run.fontSizeSp?.sp?:TextUnit.Unspecified,
            color=run.colorArgb?.let{Color(it.toULong())}?:Color.Unspecified,baselineShift=when(run.baseline){WordBaseline.Superscript->BaselineShift.Superscript;WordBaseline.Subscript->BaselineShift.Subscript;else->null})
        withStyle(style){append(run.text)}
    }
}
private fun WordAlignment.textAlign()=when(this){WordAlignment.Center->TextAlign.Center;WordAlignment.End->TextAlign.End;WordAlignment.Justify->TextAlign.Justify;else->TextAlign.Start}

@Composable
private fun WordMessage(
    title: Int,
    message: Int,
    padding: PaddingValues,
    onBack: () -> Unit,
    action: (() -> Unit)? = null,
    actionLabel: Int = R.string.try_again
) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Description, null, Modifier.size(52.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            action?.let { Button(onClick = it) { Text(stringResource(actionLabel)) } }
        }
    }
}
