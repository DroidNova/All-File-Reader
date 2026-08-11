package com.droidnova.allfilereader.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
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

sealed interface FilesLoadState { data object Loading: FilesLoadState; data class Content(val documents: List<DocumentFile>): FilesLoadState; data object Empty: FilesLoadState; data object AccessRequired: FilesLoadState; data object Error: FilesLoadState }
data class FilesUiState(val loadState: FilesLoadState = FilesLoadState.Loading, val isRefreshing: Boolean = false, val permissionPromptDismissed: Boolean = false)

@HiltViewModel
class FilesViewModel @Inject constructor(private val repository: DocumentRepository, private val access: MediaPermissionManager): ViewModel() {
    private val _uiState = MutableStateFlow(FilesUiState()); val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()
    private var job: Job? = null
    init { load(false) }
    fun refresh() = load(true)
    fun retry() = load(true)
    fun onResume() { if (access.isGranted() != (_uiState.value.loadState !is FilesLoadState.AccessRequired)) load(true) }
    fun dismissPermissionPrompt() { _uiState.value = _uiState.value.copy(permissionPromptDismissed = true) }
    fun accessRequested() { _uiState.value = _uiState.value.copy(permissionPromptDismissed = false) }
    private fun load(force: Boolean) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!access.isGranted()) { _uiState.value = FilesUiState(FilesLoadState.AccessRequired, permissionPromptDismissed = _uiState.value.permissionPromptDismissed); return@launch }
            val old = (_uiState.value.loadState as? FilesLoadState.Content)?.documents
            _uiState.value = if (old == null) FilesUiState(FilesLoadState.Loading) else FilesUiState(FilesLoadState.Content(old), isRefreshing = force)
            try { val docs = repository.getDocuments(force); _uiState.value = FilesUiState(if (docs.isEmpty()) FilesLoadState.Empty else FilesLoadState.Content(docs)) }
            catch (c: CancellationException) { throw c } catch (_: Exception) { _uiState.value = FilesUiState(if (old != null) FilesLoadState.Content(old) else FilesLoadState.Error) }
        }
    }
}
