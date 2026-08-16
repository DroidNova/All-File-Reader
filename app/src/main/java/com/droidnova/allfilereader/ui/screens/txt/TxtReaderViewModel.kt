package com.droidnova.allfilereader.ui.screens.txt

import android.content.Context
import android.net.Uri
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

sealed interface TxtReaderContent {
    data object Loading : TxtReaderContent
    data class Ready(val chunks: List<String>, val isAppending: Boolean, val endReached: Boolean) : TxtReaderContent
    data object Empty : TxtReaderContent
    data object NotFound : TxtReaderContent
    data object AccessDenied : TxtReaderContent
    data object UnsupportedEncoding : TxtReaderContent
    data object Binary : TxtReaderContent
    data object TooLarge : TxtReaderContent
    data object ReadError : TxtReaderContent
}
data class TxtReaderUiState(val fileName: String? = null, val content: TxtReaderContent = TxtReaderContent.Loading)

@HiltViewModel
class TxtReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val _uiState = MutableStateFlow(TxtReaderUiState())
    val uiState: StateFlow<TxtReaderUiState> = _uiState.asStateFlow()
    private var session: TextReaderSession? = null
    private var openJob: Job? = null
    private var appendJob: Job? = null

    init { open() }

    fun retry() = open()
    fun onResume() { if (_uiState.value.content is TxtReaderContent.AccessDenied) open() }

    fun loadMore() {
        val current = _uiState.value.content as? TxtReaderContent.Ready ?: return
        if (current.isAppending || current.endReached || appendJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(content = current.copy(isAppending = true))
        appendJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val chunk = session?.readChunk()
                withContext(Dispatchers.Main.immediate) {
                    val latest = _uiState.value.content as? TxtReaderContent.Ready ?: return@withContext
                    _uiState.value = _uiState.value.copy(content = if (chunk == null) latest.copy(isAppending = false, endReached = true)
                    else latest.copy(chunks = latest.chunks + chunk, isAppending = false))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session?.close(); session = null
                withContext(Dispatchers.Main.immediate) { showError(error) }
            }
        }
    }

    private fun open() {
        if (openJob?.isActive == true) return
        appendJob?.cancel()
        openJob = viewModelScope.launch(Dispatchers.IO) {
            session?.close(); session = null
            withContext(Dispatchers.Main.immediate) { _uiState.value = TxtReaderUiState() }
            if (!permissionManager.isGranted()) {
                withContext(Dispatchers.Main.immediate) { _uiState.value = TxtReaderUiState(content = TxtReaderContent.AccessDenied) }
                return@launch
            }
            try {
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                if (document.category != DocumentCategory.Text) throw UnsupportedTextEncodingException()
                if (document.sizeBytes > TextReaderSession.MAX_FILE_BYTES) throw TextFileTooLargeException()
                val opened = TextReaderSession.open(context.contentResolver, Uri.parse(document.uri))
                session = opened
                val first = opened.readChunk()
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = TxtReaderUiState(document.displayName,
                        if (first == null) TxtReaderContent.Empty else TxtReaderContent.Ready(listOf(first), false, false))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session?.close(); session = null
                withContext(Dispatchers.Main.immediate) { showError(error) }
            }
        }
    }

    private fun showError(error: Exception) {
        val content = when (error) {
            is SecurityException -> TxtReaderContent.AccessDenied
            is FileNotFoundException -> TxtReaderContent.NotFound
            is BinaryTextException -> TxtReaderContent.Binary
            is CharacterCodingException, is UnsupportedTextEncodingException -> TxtReaderContent.UnsupportedEncoding
            is TextFileTooLargeException -> TxtReaderContent.TooLarge
            else -> TxtReaderContent.ReadError
        }
        _uiState.value = _uiState.value.copy(content = content)
    }

    override fun onCleared() { appendJob?.cancel(); openJob?.cancel(); session?.close(); session = null; super.onCleared() }
}
