package com.droidnova.allfilereader.ui.screens.txt

import android.content.Context
import android.app.ActivityManager
import android.net.Uri
import android.util.Log
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

enum class TxtLoadingStage { Resolving, ReadingMetadata, DetectingEncoding, PreparingFirstChunk }
sealed interface TxtReaderContent {
    data class Loading(val stage: TxtLoadingStage = TxtLoadingStage.Resolving) : TxtReaderContent
    data class Ready(val chunks: List<TextChunkIndex>, val firstChunkText: String) : TxtReaderContent
    data object Empty : TxtReaderContent
    data object NotFound : TxtReaderContent
    data object AccessDenied : TxtReaderContent
    data object UnsupportedEncoding : TxtReaderContent
    data object MalformedText : TxtReaderContent
    data object Binary : TxtReaderContent
    data object TooLarge : TxtReaderContent
    data object InsufficientStorage : TxtReaderContent
    data object ReadError : TxtReaderContent
}
data class TxtSearchState(
    val active: Boolean = false,
    val searching: Boolean = false,
    val query: String = "",
    val effectiveQuery: String = "",
    val generation: Long = 0L,
    val matches: List<TextMatch> = emptyList(),
    val selected: Int = -1,
    val truncated: Boolean = false
)
data class TxtReaderUiState(val fileName: String? = null, val content: TxtReaderContent = TxtReaderContent.Loading(), val isIndexingInBackground: Boolean = false, val fontSize: Int = 16, val wordWrap: Boolean = true, val search: TxtSearchState = TxtSearchState())

@HiltViewModel
class TxtReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val budget = TextReaderBudgetPolicy.forDevice(
        TextReaderDeviceProfile(activityManager.memoryClass, activityManager.isLowRamDevice)
    )
    private val preparer = TextDocumentPreparer(context.contentResolver, context.cacheDir, budget)
    private val _uiState = MutableStateFlow(TxtReaderUiState())
    val uiState: StateFlow<TxtReaderUiState> = _uiState.asStateFlow()
    private var store: TextDocumentStore? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null
    private val loadGate = TxtLoadRequestGate()
    private var loadGeneration = 0L
    private var searchGeneration = 0L

    init {
        viewModelScope.launch {
            context.txtReaderPreferences.data.collect { values ->
                _uiState.update { it.copy(fontSize = (values[FONT_SIZE] ?: DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE), wordWrap = values[WORD_WRAP] ?: true) }
            }
        }
        trace("reader created")
        open(force = false)
    }

    fun retry() { if (openJob?.isActive != true) open(force = true) }
    fun onResume() { if (_uiState.value.content is TxtReaderContent.AccessDenied) open(force = true) }
    suspend fun chunk(index: Int): String? = withContext(Dispatchers.IO) { store?.readChunk(index) }

    fun setSearchActive(active: Boolean) {
        if (!active) { searchJob?.cancel(); _uiState.update { it.copy(search = TxtSearchState()) } }
        else _uiState.update { it.copy(search = it.search.copy(active = true)) }
    }
    fun setQuery(query: String) {
        val boundedQuery = query.take(TxtLimits.MAX_QUERY_CHARACTERS)
        searchJob?.cancel()
        val generation = ++searchGeneration
        _uiState.update {
            it.copy(search = it.search.copy(
                query = boundedQuery,
                effectiveQuery = "",
                generation = generation,
                searching = boundedQuery.isNotEmpty(),
                matches = emptyList(),
                selected = -1,
                truncated = false
            ))
        }
        if (boundedQuery.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(300)
            // Content can be read as soon as chunk one is ready, but a complete search waits
            // for the independently running indexer so later chunks are not missed.
            openJob?.join()
            _uiState.update { state ->
                if (state.search.generation != generation) state
                else state.copy(search = state.search.copy(effectiveQuery = boundedQuery))
            }
            val result = withContext(Dispatchers.IO) {
                store?.search(boundedQuery) ?: TextSearchResult(emptyList(), false)
            }
            _uiState.update { state ->
                if (state.search.generation != generation) state
                else state.copy(search = state.search.copy(
                    searching = false,
                    matches = result.matches,
                    selected = if (result.matches.isEmpty()) -1 else 0,
                    truncated = result.truncated
                ))
            }
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

    private fun open(force: Boolean) {
        val generation = loadGate.begin(documentId, force) ?: return
        loadGeneration = generation
        searchJob?.cancel(); openJob?.cancel(); closeStore()
        setContent(generation, TxtReaderContent.Loading(), fileName = null, indexing = false, resetSearch = true)
        openJob = viewModelScope.launch(Dispatchers.IO) {
            var localStore: TextDocumentStore? = null
            var phase = "resolution"
            try {
                trace("document resolution started")
                if (!permissionManager.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                trace("document resolution completed")
                trace("metadata read started")
                phase = "metadata"
                updateStage(generation, TxtLoadingStage.ReadingMetadata)
                if (document.category != DocumentCategory.Text) throw UnsupportedTextEncodingException()
                // Provider metadata is only an early-rejection hint; the streaming counter is authoritative.
                if (document.sizeBytes > TxtLimits.MAX_FILE_BYTES) throw TextFileTooLargeException()
                trace("metadata read completed")
                phase = "stream"
                updateStage(generation, TxtLoadingStage.DetectingEncoding, document.displayName)
                val prepared = preparer.prepare(
                        Uri.parse(document.uri),
                        onStreamOpened = { trace("stream opened"); phase = "first chunk" },
                        onEncodingDetected = {
                            trace("encoding detected")
                            trace("first chunk decoding started")
                            updateStage(generation, TxtLoadingStage.PreparingFirstChunk, document.displayName)
                        },
                        onChunkPrepared = { incrementallyPrepared, decodedText ->
                            withContext(Dispatchers.Main.immediate) {
                                if (generation != loadGeneration) return@withContext
                                localStore = incrementallyPrepared
                                store = incrementallyPrepared
                                val current = _uiState.value.content
                                val firstText = (current as? TxtReaderContent.Ready)?.firstChunkText ?: decodedText
                                if (current !is TxtReaderContent.Ready) {
                                    trace("first chunk decoding completed chars=${decodedText.length}")
                                    trace("first chunk emitted")
                                    trace("background indexing started")
                                    phase = "background indexing"
                                }
                                setContent(generation, TxtReaderContent.Ready(incrementallyPrepared.chunks.toList(), firstText), document.displayName, indexing = true)
                            }
                        }
                    )
                localStore = prepared
                withContext(Dispatchers.Main.immediate) {
                    if (generation != loadGeneration) return@withContext
                    store = prepared
                    val current = _uiState.value.content
                    if (prepared.chunks.isEmpty()) {
                        trace("first chunk decoding completed chars=0")
                        setContent(generation, TxtReaderContent.Empty, document.displayName, indexing = false)
                    } else {
                        val ready = current as TxtReaderContent.Ready
                        setContent(generation, ready.copy(chunks = prepared.chunks.toList()), document.displayName, indexing = false)
                        trace("background indexing completed")
                    }
                }
            } catch (cancelled: CancellationException) {
                trace("load job cancelled")
                localStore?.takeIf { it !== store }?.close()
                throw cancelled
            } catch (error: Exception) {
                localStore?.close()
                withContext(Dispatchers.Main.immediate) {
                    if (generation == loadGeneration) {
                        closeStore()
                        trace("$phase failed")
                        trace("load failed type=${error.javaClass.simpleName}")
                        setContent(generation, failureContent(error), indexing = false)
                    }
                }
            }
        }
    }
    private suspend fun updateStage(generation: Long, stage: TxtLoadingStage, name: String? = null) = withContext(Dispatchers.Main.immediate) {
        setContent(generation, TxtReaderContent.Loading(stage), name)
    }
    private fun setContent(generation: Long, content: TxtReaderContent, fileName: String? = _uiState.value.fileName, indexing: Boolean = _uiState.value.isIndexingInBackground, resetSearch: Boolean = false) {
        if (generation != loadGeneration) return
        val previous = _uiState.value.content.traceName()
        _uiState.update { it.copy(fileName = fileName, content = content, isIndexingInBackground = indexing, search = if (resetSearch) TxtSearchState() else it.search) }
        val next = content.traceName()
        if (previous != next) trace("state $previous -> $next")
    }
    private fun closeStore() { store?.close(); store = null }
    override fun onCleared() { trace("reader disposed"); searchJob?.cancel(); openJob?.cancel(); closeStore(); super.onCleared() }

    companion object {
        const val MIN_FONT_SIZE = 12; const val MAX_FONT_SIZE = 32; const val DEFAULT_FONT_SIZE = 16; const val FONT_STEP = 2
        private val FONT_SIZE = intPreferencesKey("font_size_sp"); private val WORD_WRAP = booleanPreferencesKey("word_wrap")
    }
}

internal fun wrappedMatchIndex(current: Int, delta: Int, count: Int): Int = if (count <= 0) -1 else (current + delta).mod(count)

internal fun failureContent(error: Exception): TxtReaderContent = when (error) {
    is SecurityException -> TxtReaderContent.AccessDenied
    is FileNotFoundException -> TxtReaderContent.NotFound
    is BinaryTextException -> TxtReaderContent.Binary
    is CharacterCodingException -> TxtReaderContent.MalformedText
    is UnsupportedTextEncodingException -> TxtReaderContent.UnsupportedEncoding
    is TextFileTooLargeException -> TxtReaderContent.TooLarge
    is InsufficientTextStorageException -> TxtReaderContent.InsufficientStorage
    else -> TxtReaderContent.ReadError
}

internal class TxtLoadRequestGate {
    private var documentId: String? = null
    private var generation = 0L

    @Synchronized fun begin(stableDocumentId: String, force: Boolean = false): Long? {
        if (!force && stableDocumentId == documentId) return null
        documentId = stableDocumentId
        return ++generation
    }

    @Synchronized fun isCurrent(candidate: Long): Boolean = candidate == generation
}

private fun TxtReaderContent.traceName(): String = when (this) {
    is TxtReaderContent.Loading -> stage.name.uppercase()
    is TxtReaderContent.Ready -> "READY"
    TxtReaderContent.Empty -> "EMPTY"
    TxtReaderContent.NotFound -> "FILE_MISSING"
    TxtReaderContent.AccessDenied -> "PERMISSION_DENIED"
    TxtReaderContent.UnsupportedEncoding -> "UNSUPPORTED_ENCODING"
    TxtReaderContent.MalformedText -> "MALFORMED_TEXT"
    TxtReaderContent.Binary -> "LIKELY_BINARY"
    TxtReaderContent.TooLarge -> "FILE_TOO_LARGE"
    TxtReaderContent.InsufficientStorage -> "INSUFFICIENT_STORAGE"
    TxtReaderContent.ReadError -> "FAILURE"
}

private fun trace(message: String) {
    if (BuildConfig.DEBUG) Log.d("TxtReaderTrace", message)
}
