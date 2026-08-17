package com.droidnova.allfilereader.ui.screens.txt

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.text.*
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.nio.charset.CharacterCodingException
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val Context.txtReaderPreferences by preferencesDataStore("txt_reader_preferences")

enum class TxtLoadingStage { Resolving, ReadingMetadata, DetectingEncoding, PreparingContent }
sealed interface TxtReaderContent {
    data class Loading(val stage: TxtLoadingStage = TxtLoadingStage.Resolving) : TxtReaderContent
    data class Ready(val chunks: List<TextChunkIndex>) : TxtReaderContent
    data object Empty : TxtReaderContent
    data object NotFound : TxtReaderContent
    data object AccessDenied : TxtReaderContent
    data object UnsupportedEncoding : TxtReaderContent
    data object Binary : TxtReaderContent
    data object TooLarge : TxtReaderContent
    data object ReadError : TxtReaderContent
}
data class TxtSearchState(val active: Boolean = false, val searching: Boolean = false, val query: String = "", val matches: List<TextMatch> = emptyList(), val selected: Int = -1, val truncated: Boolean = false)
data class TxtReaderUiState(val fileName: String? = null, val content: TxtReaderContent = TxtReaderContent.Loading(), val fontSize: Int = 16, val wordWrap: Boolean = true, val search: TxtSearchState = TxtSearchState())

@HiltViewModel
class TxtReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val preparer = TextDocumentPreparer(context.contentResolver, context.cacheDir)
    private val _uiState = MutableStateFlow(TxtReaderUiState())
    val uiState: StateFlow<TxtReaderUiState> = _uiState.asStateFlow()
    private var store: TextDocumentStore? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            context.txtReaderPreferences.data.collect { values ->
                _uiState.update { it.copy(fontSize = (values[FONT_SIZE] ?: DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE), wordWrap = values[WORD_WRAP] ?: true) }
            }
        }
        open()
    }

    fun retry() { if (openJob?.isActive != true) open() }
    fun onResume() { if (_uiState.value.content is TxtReaderContent.AccessDenied) open() }
    suspend fun chunk(index: Int): String? = withContext(Dispatchers.IO) { store?.readChunk(index) }

    fun setSearchActive(active: Boolean) {
        if (!active) { searchJob?.cancel(); _uiState.update { it.copy(search = TxtSearchState()) } }
        else _uiState.update { it.copy(search = it.search.copy(active = true)) }
    }
    fun setQuery(query: String) {
        val boundedQuery = query.take(TxtLimits.MAX_QUERY_CHARACTERS)
        _uiState.update { it.copy(search = it.search.copy(query = boundedQuery, searching = boundedQuery.isNotEmpty(), matches = emptyList(), selected = -1, truncated = false)) }
        searchJob?.cancel()
        if (boundedQuery.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(300)
            val result = withContext(Dispatchers.Default) { store?.search(boundedQuery) ?: TextSearchResult(emptyList(), false) }
            _uiState.update { state -> if (state.search.query != boundedQuery) state else state.copy(search = state.search.copy(searching = false, matches = result.matches, selected = if (result.matches.isEmpty()) -1 else 0, truncated = result.truncated)) }
        }
    }
    fun nextMatch() = moveMatch(1)
    fun previousMatch() = moveMatch(-1)
    private fun moveMatch(delta: Int) = _uiState.update { state ->
        val count = state.search.matches.size
        if (count == 0) state else state.copy(search = state.search.copy(selected = wrappedMatchIndex(state.search.selected, delta, count)))
    }

    fun setFontSize(size: Int) {
        val safe = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        _uiState.update { it.copy(fontSize = safe) }
        viewModelScope.launch { context.txtReaderPreferences.edit { it[FONT_SIZE] = safe } }
    }
    fun resetFontSize() = setFontSize(DEFAULT_FONT_SIZE)
    fun setWordWrap(enabled: Boolean) {
        _uiState.update { it.copy(wordWrap = enabled) }
        viewModelScope.launch { context.txtReaderPreferences.edit { it[WORD_WRAP] = enabled } }
    }

    private fun open() {
        searchJob?.cancel(); openJob?.cancel(); closeStore(); _uiState.update { it.copy(fileName = null, content = TxtReaderContent.Loading(), search = TxtSearchState()) }
        openJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!permissionManager.isGranted()) throw SecurityException()
                updateStage(TxtLoadingStage.ReadingMetadata)
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                if (document.category != DocumentCategory.Text) throw UnsupportedTextEncodingException()
                if (document.sizeBytes > TxtLimits.MAX_FILE_BYTES) throw TextFileTooLargeException()
                updateStage(TxtLoadingStage.DetectingEncoding, document.displayName)
                val prepared = withTimeout(30_000L) { preparer.prepare(Uri.parse(document.uri)) { updateStage(TxtLoadingStage.PreparingContent, document.displayName) } }
                withContext(Dispatchers.Main.immediate) { _uiState.update { it.copy(fileName = document.displayName, content = if (prepared.chunks.isEmpty()) TxtReaderContent.Empty else TxtReaderContent.Ready(prepared.chunks)) } }
            } catch (_: TimeoutCancellationException) { closeStore(); withContext(Dispatchers.Main.immediate) { showError(java.io.IOException()) } }
            catch (cancelled: CancellationException) { closeStore(); throw cancelled }
            catch (error: Exception) { closeStore(); withContext(Dispatchers.Main.immediate) { showError(error) } }
        }
    }
    private suspend fun updateStage(stage: TxtLoadingStage, name: String? = null) = withContext(Dispatchers.Main.immediate) { _uiState.update { it.copy(fileName = name ?: it.fileName, content = TxtReaderContent.Loading(stage)) } }
    private fun showError(error: Exception) {
        val content = when (error) {
            is SecurityException -> TxtReaderContent.AccessDenied; is FileNotFoundException -> TxtReaderContent.NotFound
            is BinaryTextException -> TxtReaderContent.Binary; is CharacterCodingException, is UnsupportedTextEncodingException -> TxtReaderContent.UnsupportedEncoding
            is TextFileTooLargeException -> TxtReaderContent.TooLarge; else -> TxtReaderContent.ReadError
        }
        _uiState.update { it.copy(content = content) }
    }
    private fun closeStore() { store?.close(); store = null }
    override fun onCleared() { searchJob?.cancel(); openJob?.cancel(); closeStore(); super.onCleared() }

    companion object {
        const val MIN_FONT_SIZE = 12; const val MAX_FONT_SIZE = 32; const val DEFAULT_FONT_SIZE = 16; const val FONT_STEP = 2
        private val FONT_SIZE = intPreferencesKey("font_size_sp"); private val WORD_WRAP = booleanPreferencesKey("word_wrap")
    }
}

internal fun wrappedMatchIndex(current: Int, delta: Int, count: Int): Int = if (count <= 0) -1 else (current + delta).mod(count)
