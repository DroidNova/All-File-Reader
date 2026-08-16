package com.droidnova.allfilereader.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest

data class FilesUiState(val hasAccess: Boolean, val permissionPromptDismissed: Boolean = false)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val access: MediaPermissionManager
) : ViewModel() {
    private val generation = MutableStateFlow(0)
    val documents: Flow<PagingData<DocumentFile>> = generation.flatMapLatest { repository.pagedDocuments() }.cachedIn(viewModelScope)
    private val _uiState = MutableStateFlow(FilesUiState(access.isGranted()))
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    fun prepareRefresh() = repository.requestPagingRefresh()
    fun onResume() {
        val now = access.isGranted()
        if (now != _uiState.value.hasAccess) {
            _uiState.value = _uiState.value.copy(hasAccess = now)
            if (now) { repository.requestPagingRefresh(); generation.value++ }
        }
    }
    fun dismissPermissionPrompt() { _uiState.value = _uiState.value.copy(permissionPromptDismissed = true) }
}
