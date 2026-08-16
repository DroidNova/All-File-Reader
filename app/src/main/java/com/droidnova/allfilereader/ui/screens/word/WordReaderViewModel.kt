package com.droidnova.allfilereader.ui.screens.word

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WordReaderContent {
    data object Loading : WordReaderContent
    data class Ready(val bytes: ByteArray, val sessionId: String) : WordReaderContent
    data object LegacyDoc : WordReaderContent
    data object Missing : WordReaderContent
    data object AccessDenied : WordReaderContent
    data object SafetyLimit : WordReaderContent
    data object Failure : WordReaderContent
}

data class WordReaderUiState(val fileName: String? = null, val content: WordReaderContent = WordReaderContent.Loading)

@HiltViewModel
class WordReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val _uiState = MutableStateFlow(WordReaderUiState())
    val uiState: StateFlow<WordReaderUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { load() }
    fun retry() = load()
    fun onResume() { if (_uiState.value.content is WordReaderContent.AccessDenied) load() }

    private fun load() {
        loadJob?.cancel()
        _uiState.value = WordReaderUiState()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!permissionManager.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                val isDocx = document.extension.equals("docx", true) ||
                    document.mimeType.equals(DOCX_MIME, true)
                if (!isDocx) {
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.value = WordReaderUiState(document.displayName, WordReaderContent.LegacyDoc)
                    }
                    return@launch
                }
                val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(document.uri))?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOCX_BYTES) throw DocumentTooLargeException()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw FileNotFoundException()
                if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
                    throw InvalidDocumentException()
                }
                val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = WordReaderUiState(document.displayName, WordReaderContent.Ready(bytes, token))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    val content = when (error) {
                        is SecurityException -> WordReaderContent.AccessDenied
                        is FileNotFoundException -> WordReaderContent.Missing
                        is DocumentTooLargeException -> WordReaderContent.SafetyLimit
                        else -> WordReaderContent.Failure
                    }
                    _uiState.value = _uiState.value.copy(content = content)
                }
            }
        }
    }

    private class DocumentTooLargeException : Exception()
    private class InvalidDocumentException : Exception()
    private companion object {
        const val MAX_DOCX_BYTES = 64L * 1024L * 1024L
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
