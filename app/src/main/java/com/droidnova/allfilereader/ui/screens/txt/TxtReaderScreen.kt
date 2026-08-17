package com.droidnova.allfilereader.ui.screens.txt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.data.text.TextChunkIndex
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtReaderScreen(onBack: () -> Unit, viewModel: TxtReaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var controls by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) { viewModel.onResume(); onPauseOrDispose {} }
    BackHandler(state.search.active) { viewModel.setSearchActive(false) }
    val requestAccess = rememberStorageAccessRequest(viewModel::onResume)
    if (controls) ModalBottomSheet(onDismissRequest = { controls = false }) {
        ReadingControls(state.fontSize, state.wordWrap,
            onFontSize = { size -> val index = listState.firstVisibleItemIndex; val offset = listState.firstVisibleItemScrollOffset; viewModel.setFontSize(size); scope.launch { listState.scrollToItem(index, offset) } },
            onReset = { val index = listState.firstVisibleItemIndex; val offset = listState.firstVisibleItemScrollOffset; viewModel.resetFontSize(); scope.launch { listState.scrollToItem(index, offset) } },
            onWrap = { wrap -> val index = listState.firstVisibleItemIndex; val offset = listState.firstVisibleItemScrollOffset; viewModel.setWordWrap(wrap); scope.launch { listState.scrollToItem(index, offset) } })
    }
    LaunchedEffect(state.search.selected) {
        val selected = state.search.matches.getOrNull(state.search.selected) ?: return@LaunchedEffect
        listState.animateScrollToItem(selected.chunkIndex)
    }
    Scaffold(topBar = {
        if (state.search.active) TxtSearchBar(state, viewModel)
        else TopAppBar(
            title = { Text(state.fileName ?: stringResource(R.string.txt_reader), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (state.content is TxtReaderContent.Ready) {
                    IconButton(onClick = { viewModel.setSearchActive(true) }) { Icon(Icons.Default.Search, stringResource(R.string.txt_search)) }
                    IconButton(onClick = { controls = true }) { Icon(Icons.Outlined.TextFields, stringResource(R.string.txt_reading_controls)) }
                }
            })
    }) { padding ->
        when (val content = state.content) {
            is TxtReaderContent.Loading -> Loading(padding)
            is TxtReaderContent.Ready -> TextChunks(content.chunks, state, viewModel, listState, padding)
            TxtReaderContent.Empty -> TxtMessage(R.string.txt_empty, R.string.txt_empty_message, padding, onBack)
            TxtReaderContent.NotFound -> TxtMessage(R.string.txt_not_found, R.string.txt_not_found_message, padding, onBack, viewModel::retry)
            TxtReaderContent.AccessDenied -> TxtMessage(R.string.txt_access_removed, R.string.txt_access_removed_message, padding, onBack, requestAccess, R.string.allow_access)
            TxtReaderContent.UnsupportedEncoding -> TxtMessage(R.string.txt_cannot_open, R.string.txt_encoding_unsupported, padding, onBack)
            TxtReaderContent.Binary -> TxtMessage(R.string.txt_cannot_open, R.string.txt_binary_unsupported, padding, onBack)
            TxtReaderContent.TooLarge -> TxtMessage(R.string.txt_cannot_open, R.string.txt_too_large, padding, onBack)
            TxtReaderContent.ReadError -> TxtMessage(R.string.txt_cannot_open, R.string.txt_read_error, padding, onBack, viewModel::retry)
        }
    }
}

@Composable private fun Loading(padding: PaddingValues) { val label = stringResource(R.string.txt_loading); Box(Modifier.fillMaxSize().padding(padding).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TxtSearchBar(state: TxtReaderUiState, viewModel: TxtReaderViewModel) {
    val search = state.search
    val count = if (search.truncated) stringResource(R.string.txt_search_count_limited, if (search.selected < 0) 0 else search.selected + 1) else stringResource(R.string.txt_search_count, if (search.selected < 0) 0 else search.selected + 1, search.matches.size)
    TopAppBar(title = { TextField(search.query, viewModel::setQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.txt_search)) },
        keyboardActions = KeyboardActions(onSearch = { viewModel.nextMatch() }), trailingIcon = { if (search.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(count, style = MaterialTheme.typography.labelSmall) }) },
        navigationIcon = { IconButton(onClick = { viewModel.setSearchActive(false) }) { Icon(Icons.Default.Close, stringResource(R.string.txt_close_search)) } }, actions = {
            IconButton(enabled = search.matches.isNotEmpty(), onClick = viewModel::previousMatch) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.txt_previous_result)) }
            IconButton(enabled = search.matches.isNotEmpty(), onClick = viewModel::nextMatch) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.txt_next_result)) }
        })
}

@Composable private fun TextChunks(chunks: List<TextChunkIndex>, state: TxtReaderUiState, viewModel: TxtReaderViewModel, listState: androidx.compose.foundation.lazy.LazyListState, padding: PaddingValues) {
    val horizontal = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        items(chunks.size, key = { it }) { index ->
            val text by produceState<String?>(null, index) { value = viewModel.chunk(index) }
            val value = text
            if (value == null) Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else Text(highlight(value, chunks[index], state), fontSize = state.fontSize.sp, lineHeight = (state.fontSize + 8).sp, softWrap = state.wordWrap,
                modifier = if (state.wordWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontal).widthIn(min = 1.dp))
        }
    }
}

@Composable private fun highlight(text: String, chunk: TextChunkIndex, state: TxtReaderUiState): AnnotatedString {
    if (!state.search.active || state.search.query.isEmpty()) return AnnotatedString(text)
    val normal = MaterialTheme.colorScheme.tertiaryContainer; val current = MaterialTheme.colorScheme.primaryContainer
    val chunkEnd = chunk.characterOffset + chunk.characterLength
    val storeMatches = state.search.matches.withIndex().filter { it.value.characterOffset < chunkEnd && it.value.characterOffset + state.search.query.length > chunk.characterOffset }
    if (storeMatches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        storeMatches.forEach { indexed ->
            val start = (indexed.value.characterOffset - chunk.characterOffset).toInt().coerceIn(0, text.length)
            val end = (indexed.value.characterOffset + state.search.query.length - chunk.characterOffset).toInt().coerceIn(0, text.length)
            if (start < end) addStyle(SpanStyle(background = if (indexed.index == state.search.selected) current else normal), start, end)
        }
    }
}

@Composable private fun ReadingControls(fontSize: Int, wrap: Boolean, onFontSize: (Int) -> Unit, onReset: () -> Unit, onWrap: (Boolean) -> Unit) {
    val decreaseLabel = stringResource(R.string.txt_decrease_font_size)
    val increaseLabel = stringResource(R.string.txt_increase_font_size)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.txt_reading_controls), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = fontSize > TxtReaderViewModel.MIN_FONT_SIZE, onClick = { onFontSize(fontSize - TxtReaderViewModel.FONT_STEP) }) { Text("−", Modifier.semantics { contentDescription = decreaseLabel }) }
            Text(stringResource(R.string.txt_font_size_value, fontSize))
            OutlinedButton(enabled = fontSize < TxtReaderViewModel.MAX_FONT_SIZE, onClick = { onFontSize(fontSize + TxtReaderViewModel.FONT_STEP) }) { Text("+", Modifier.semantics { contentDescription = increaseLabel }) }
        }
        TextButton(onClick = onReset) { Text(stringResource(R.string.txt_reset_font_size)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.txt_word_wrap)); Switch(wrap, onWrap) }
    }
}

@Composable private fun TxtMessage(title: Int, message: Int, padding: PaddingValues, onBack: () -> Unit, action: (() -> Unit)? = null, actionLabel: Int = R.string.try_again) {
    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(52.dp)); Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }; action?.let { Button(onClick = it) { Text(stringResource(actionLabel)) } } }
    }
}
