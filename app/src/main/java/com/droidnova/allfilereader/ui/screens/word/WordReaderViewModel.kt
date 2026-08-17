package com.droidnova.allfilereader.ui.screens.word

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.word.DocxPreflight
import com.droidnova.allfilereader.data.word.DocxPreflightException
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    data class Ready(val file: File, val sessionId: String) : WordReaderContent
    data object LegacyDoc : WordReaderContent
    data object Unsupported : WordReaderContent
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
    @ApplicationContext context: Context
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val preflight = DocxPreflight(context.contentResolver, context.cacheDir)
    private val _uiState = MutableStateFlow(WordReaderUiState())
    val uiState: StateFlow<WordReaderUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var sessionFile: File? = null

    init { load() }
    fun retry() { if (loadJob?.isActive != true) load() }
    fun onResume() { if (_uiState.value.content is WordReaderContent.AccessDenied) load() }

    private fun load() {
        loadJob?.cancel()
        deleteSession()
        _uiState.value = WordReaderUiState()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!permissionManager.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                if (document.extension.equals("doc", true) || document.mimeType.equals("application/msword", true)) {
                    show(document.displayName, WordReaderContent.LegacyDoc)
                    return@launch
                }
                if (!document.extension.equals("docx", true) && !document.mimeType.equals(DOCX_MIME, true)) {
                    show(document.displayName, WordReaderContent.Unsupported)
                    return@launch
                }
                val file = preflight.copyAndValidate(Uri.parse(document.uri))
                sessionFile = file
                val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                show(document.displayName, WordReaderContent.Ready(file, token))
            } catch (cancelled: CancellationException) {
                deleteSession()
                throw cancelled
            } catch (error: Exception) {
                deleteSession()
                val content = when (error) {
                    is SecurityException -> WordReaderContent.AccessDenied
                    is FileNotFoundException -> WordReaderContent.Missing
                    is DocxPreflightException.Unsafe -> WordReaderContent.SafetyLimit
                    is DocxPreflightException.Unsupported -> WordReaderContent.Unsupported
                    else -> WordReaderContent.Failure
                }
                withContext(Dispatchers.Main.immediate) { _uiState.value = _uiState.value.copy(content = content) }
            }
        }
    }

    private suspend fun show(name: String, content: WordReaderContent) = withContext(Dispatchers.Main.immediate) {
        _uiState.value = WordReaderUiState(name, content)
    }

    private fun deleteSession() { sessionFile?.delete(); sessionFile = null }
    override fun onCleared() { loadJob?.cancel(); deleteSession(); super.onCleared() }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
