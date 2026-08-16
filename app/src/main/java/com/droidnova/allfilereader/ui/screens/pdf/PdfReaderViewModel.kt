package com.droidnova.allfilereader.ui.screens.pdf

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PdfDocumentState {
    data object Loading : PdfDocumentState
    data class Ready(val fileName: String, val uri: Uri) : PdfDocumentState
    data object NotFound : PdfDocumentState
    data object AccessDenied : PdfDocumentState
    data object Unsupported : PdfDocumentState
}

data class PdfReaderUiState(val document: PdfDocumentState = PdfDocumentState.Loading)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager
) : ViewModel() {
    private val documentId = savedStateHandle.get<String>("documentId").orEmpty()
    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()
    private var resolutionJob: Job? = null

    init { resolve() }

    fun retry() = resolve()

    fun onResume() {
        if (_uiState.value.document is PdfDocumentState.AccessDenied) resolve()
    }

    fun viewerUnsupported() {
        _uiState.value = PdfReaderUiState(PdfDocumentState.Unsupported)
    }

    private fun resolve() {
        if (resolutionJob?.isActive == true) return
        resolutionJob = viewModelScope.launch {
            _uiState.value = PdfReaderUiState()
            if (!permissionManager.isGranted()) {
                _uiState.value = PdfReaderUiState(PdfDocumentState.AccessDenied)
                return@launch
            }
            try {
                val document = repository.resolveDocument(documentId)
                if (document == null) {
                    _uiState.value = PdfReaderUiState(PdfDocumentState.NotFound)
                } else if (document.category != DocumentCategory.Pdf) {
                    _uiState.value = PdfReaderUiState(PdfDocumentState.Unsupported)
                } else {
                    val uri = Uri.parse(document.uri)
                    val readable = uri.scheme != "file" || File(URI(document.uri)).isFile
                    _uiState.value = PdfReaderUiState(
                        if (readable) PdfDocumentState.Ready(document.displayName, uri)
                        else PdfDocumentState.NotFound
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                _uiState.value = PdfReaderUiState(PdfDocumentState.AccessDenied)
            } catch (_: UnsupportedOperationException) {
                _uiState.value = PdfReaderUiState(PdfDocumentState.Unsupported)
            } catch (_: Exception) {
                _uiState.value = PdfReaderUiState(PdfDocumentState.NotFound)
            }
        }
    }
}
