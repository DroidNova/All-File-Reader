package com.droidnova.allfilereader.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.permission.RequiredMediaPermission
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FilesLoadState {
    data object Loading : FilesLoadState
    data class Content(val documents: List<DocumentFile>) : FilesLoadState
    data object Empty : FilesLoadState
    data object Error : FilesLoadState
}

data class FilesUiState(
    val loadState: FilesLoadState = FilesLoadState.Loading,
    val requiredPermission: RequiredMediaPermission? = null,
    val permissionPromptDismissed: Boolean = false
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadFiles()
    }

    fun refresh() = loadFiles(forceRefresh = true)

    fun retry() = loadFiles(forceRefresh = true)

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            loadFiles(forceRefresh = true)
        } else {
            _uiState.value = _uiState.value.copy(permissionPromptDismissed = true)
            if (!permissionManager.canQueryDocumentsWithoutPermission()) {
                _uiState.value = _uiState.value.copy(loadState = FilesLoadState.Empty)
            }
        }
    }

    fun dismissPermissionPrompt() {
        _uiState.value = _uiState.value.copy(permissionPromptDismissed = true)
    }

    private fun loadFiles(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val permissionGranted = permissionManager.isGranted()
            val requiredPermission = permissionManager.requiredPermission().takeUnless {
                permissionGranted
            }
            if (!permissionGranted && !permissionManager.canQueryDocumentsWithoutPermission()) {
                _uiState.value = FilesUiState(
                    loadState = FilesLoadState.Empty,
                    requiredPermission = requiredPermission
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                loadState = FilesLoadState.Loading,
                requiredPermission = requiredPermission,
                permissionPromptDismissed = false
            )
            try {
                val documents = repository.getDocuments(
                    includeImages = permissionGranted,
                    forceRefresh = forceRefresh
                )
                _uiState.value = FilesUiState(
                    loadState = if (documents.isEmpty()) {
                        FilesLoadState.Empty
                    } else {
                        FilesLoadState.Content(documents)
                    },
                    requiredPermission = requiredPermission
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = FilesUiState(
                    loadState = FilesLoadState.Error,
                    requiredPermission = requiredPermission
                )
            }
        }
    }
}
