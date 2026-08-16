package com.droidnova.allfilereader.ui.screens.word

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.word.*
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.WordBlock
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface WordReaderContent {
    data object Loading : WordReaderContent
    data class Ready(val batches: List<List<WordBlock>>, val images: Map<String, Bitmap>, val isParsing: Boolean) : WordReaderContent
    data object Empty : WordReaderContent
    data object LegacyDoc : WordReaderContent
    data object Encrypted : WordReaderContent
    data object Corrupted : WordReaderContent
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
    private val parser = DocxParser()
    private val _uiState = MutableStateFlow(WordReaderUiState())
    val uiState: StateFlow<WordReaderUiState> = _uiState.asStateFlow()
    private var parseJob: Job? = null

    init { load() }
    fun retry() = load()
    fun onResume() { if (_uiState.value.content is WordReaderContent.AccessDenied) load() }

    private fun load() {
        if (parseJob?.isActive == true) return
        recycleImages(); _uiState.value = WordReaderUiState()
        parseJob = viewModelScope.launch(Dispatchers.IO) {
            var temporary: File? = null
            try {
                if (!permissionManager.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(documentId) ?: throw FileNotFoundException()
                val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                val isDocx = document.extension.equals("docx", true) || document.mimeType.equals(docxMime, true)
                if (!isDocx) {
                    withContext(Dispatchers.Main.immediate) { _uiState.value = WordReaderUiState(document.displayName, WordReaderContent.LegacyDoc) }
                    return@launch
                }
                val tempFile = File.createTempFile("word_reader_", ".docx", context.cacheDir)
                temporary = tempFile
                context.contentResolver.openInputStream(android.net.Uri.parse(document.uri))?.use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024); val signature = ByteArray(4); var signatureCount = 0; var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            if (signatureCount < 4) {
                                val copied = minOf(4 - signatureCount, count)
                                buffer.copyInto(signature, signatureCount, 0, copied); signatureCount += copied
                            }
                            total += count; if (total > MAX_COMPRESSED_BYTES) throw UnsafeDocxException()
                            output.write(buffer, 0, count)
                        }
                        if (signatureCount < 4) throw InvalidDocxException()
                        if (signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) throw EncryptedDocxException()
                    }
                } ?: throw FileNotFoundException()
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = WordReaderUiState(document.displayName, WordReaderContent.Ready(emptyList(), emptyMap(), true))
                }
                val result = parser.parse(tempFile) { batch ->
                    withContext(Dispatchers.Main.immediate) {
                        val ready = _uiState.value.content as? WordReaderContent.Ready ?: return@withContext
                        _uiState.value = _uiState.value.copy(content = ready.copy(batches = ready.batches + listOf(batch)))
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    val ready = _uiState.value.content as? WordReaderContent.Ready
                    _uiState.value = _uiState.value.copy(content = if (result.blockCount == 0) WordReaderContent.Empty
                    else ready?.copy(images = result.images, isParsing = false) ?: WordReaderContent.Failure)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { withContext(Dispatchers.Main.immediate) { showError(error) } }
            finally { temporary?.delete() }
        }
    }

    private fun showError(error: Exception) {
        val content = when (error) {
            is SecurityException -> WordReaderContent.AccessDenied
            is FileNotFoundException -> WordReaderContent.Missing
            is EncryptedDocxException -> WordReaderContent.Encrypted
            is UnsafeDocxException -> WordReaderContent.SafetyLimit
            is InvalidDocxException -> WordReaderContent.Corrupted
            else -> WordReaderContent.Failure
        }
        _uiState.value = _uiState.value.copy(content = content)
    }

    private fun recycleImages() {
        ((_uiState.value.content as? WordReaderContent.Ready)?.images)?.values?.forEach { if (!it.isRecycled) it.recycle() }
    }
    override fun onCleared() { parseJob?.cancel(); recycleImages(); super.onCleared() }
    private companion object { const val MAX_COMPRESSED_BYTES = 64L * 1024L * 1024L }
}
