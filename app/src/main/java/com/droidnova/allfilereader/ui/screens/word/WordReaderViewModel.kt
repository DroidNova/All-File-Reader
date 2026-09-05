package com.droidnova.allfilereader.ui.screens.word

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droidnova.allfilereader.BuildConfig
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.word.DocxPreflight
import com.droidnova.allfilereader.data.word.DocxPreflightException
import com.droidnova.allfilereader.data.word.DocxBudgetPolicy
import com.droidnova.allfilereader.data.word.DocxSessionFiles
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
    data class Ready(val file: File, val sessionId: String, val maxHighlights: Int) : WordReaderContent
    data object LegacyDoc : WordReaderContent
    data object Unsupported : WordReaderContent
    data object Missing : WordReaderContent
    data object AccessDenied : WordReaderContent
    data object SafetyLimit : WordReaderContent
    data object TooLarge : WordReaderContent
    data object TooComplex : WordReaderContent
    data object MissingParts : WordReaderContent
    data object RendererStalled : WordReaderContent
    data object RendererFailure : WordReaderContent
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
    private val sessionDirectory = File(context.cacheDir,"docx_sessions").apply { mkdirs() }
    private val preflight = DocxPreflight(context.contentResolver, sessionDirectory)
    private val budget = DocxBudgetPolicy.from(context)
    private val _uiState = MutableStateFlow(WordReaderUiState())
    val uiState: StateFlow<WordReaderUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var sessionFile: File? = null
    private var attemptId = 0L

    init { cleanStaleSessions();load() }
    fun retry() { load() }
    fun onResume() { if (_uiState.value.content is WordReaderContent.AccessDenied) load() }

    private fun load() {
        loadJob?.cancel()
        deleteSession()
        val attempt = ++attemptId
        _uiState.value = WordReaderUiState()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!permissionManager.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                if(BuildConfig.DEBUG)Log.i(TAG,"attempt=$attempt stage=resolved declaredSizeKnown=${document.sizeBytes>=0} budget=${budget.profileName}")
                if (document.extension.equals("doc", true) || document.mimeType.equals("application/msword", true)) {
                    show(document.displayName, WordReaderContent.LegacyDoc)
                    return@launch
                }
                if (!document.extension.equals("docx", true) && !document.mimeType.equals(DOCX_MIME, true)) {
                    show(document.displayName, WordReaderContent.Unsupported)
                    return@launch
                }
                val session = preflight.copyAndValidate(Uri.parse(document.uri),document.sizeBytes,budget)
                kotlinx.coroutines.ensureActive()
                if(attempt!=attemptId){session.file.delete();return@launch}
                sessionFile = session.file
                if(BuildConfig.DEBUG)Log.i(TAG,"attempt=$attempt stage=validated actualBytes=${session.actualBytes} entries=${session.entryCount} uncompressedBytes=${session.totalUncompressedBytes}")
                val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                show(document.displayName, WordReaderContent.Ready(session.file, token,budget.maxSearchHighlightCount))
            } catch (cancelled: CancellationException) {
                if(BuildConfig.DEBUG)Log.i(TAG,"attempt=$attempt stage=cleanup reason=cancelled")
                deleteSession()
                throw cancelled
            } catch (error: Exception) {
                deleteSession()
                val content = when (error) {
                    is SecurityException -> WordReaderContent.AccessDenied
                    is FileNotFoundException -> WordReaderContent.Missing
                    is DocxPreflightException.TooLarge -> WordReaderContent.TooLarge
                    is DocxPreflightException.TooComplex -> WordReaderContent.TooComplex
                    is DocxPreflightException.MissingParts -> WordReaderContent.MissingParts
                    is DocxPreflightException.Unsafe -> WordReaderContent.SafetyLimit
                    is DocxPreflightException.Unsupported -> WordReaderContent.Unsupported
                    else -> WordReaderContent.Failure
                }
                if(BuildConfig.DEBUG)Log.w(TAG,"attempt=$attempt stage=failed code=${content.javaClass.simpleName} exception=${error.javaClass.simpleName}")
                withContext(Dispatchers.Main.immediate) { _uiState.value = _uiState.value.copy(content = content) }
            }
        }
    }

    private suspend fun show(name: String, content: WordReaderContent) = withContext(Dispatchers.Main.immediate) {
        _uiState.value = WordReaderUiState(name, content)
    }

    fun viewerFailed(sessionId:String,stalled:Boolean){val ready=_uiState.value.content as? WordReaderContent.Ready?:return;if(ready.sessionId!=sessionId)return;deleteSession();_uiState.value=_uiState.value.copy(content=if(stalled)WordReaderContent.RendererStalled else WordReaderContent.RendererFailure)}
    private fun deleteSession() { DocxSessionFiles.deleteOwned(sessionFile,sessionDirectory);sessionFile = null }
    private fun cleanStaleSessions(){DocxSessionFiles.cleanStale(sessionDirectory,System.currentTimeMillis(),sessionFile)}
    override fun onCleared() { attemptId++;loadJob?.cancel(); deleteSession(); super.onCleared() }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val TAG="DocxReaderTrace"
    }
}
