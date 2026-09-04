package com.droidnova.allfilereader.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

enum class RecentDocumentFilter(val category: com.droidnova.allfilereader.domain.model.DocumentCategory?) {
    All(null), Pdf(com.droidnova.allfilereader.domain.model.DocumentCategory.Pdf),
    Word(com.droidnova.allfilereader.domain.model.DocumentCategory.Word),
    Excel(com.droidnova.allfilereader.domain.model.DocumentCategory.Excel),
    PowerPoint(com.droidnova.allfilereader.domain.model.DocumentCategory.PowerPoint),
    Text(com.droidnova.allfilereader.domain.model.DocumentCategory.Text);

    fun matches(document: DocumentFile): Boolean = category == null ||
        com.droidnova.allfilereader.domain.model.DocumentClassifier.classify(
            document.mimeType,
            document.extension ?: com.droidnova.allfilereader.domain.model.DocumentClassifier.extensionOf(document.displayName)
        ) == category
}

data class FilesUiState(val hasAccess: Boolean, val permissionPromptDismissed: Boolean = false)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val access: MediaPermissionManager,
    private val favoritesRepository: FavoritesRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val generation = MutableStateFlow(0)
    val selectedFilter = savedStateHandle.getStateFlow(FILTER_KEY, RecentDocumentFilter.All)
    val documents: Flow<PagingData<DocumentFile>> = kotlinx.coroutines.flow.combine(generation, selectedFilter) { _, filter -> filter }
        .flatMapLatest { repository.pagedDocuments(it.category) }.cachedIn(viewModelScope)
    private val _uiState = MutableStateFlow(FilesUiState(access.isGranted()))
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()
    val favoriteIds = favoritesRepository.favoriteIds
    private val _favoriteUpdates = MutableStateFlow<Set<String>>(emptySet())
    val favoriteUpdates: StateFlow<Set<String>> = _favoriteUpdates.asStateFlow()
    private val _favoriteErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val favoriteErrors = _favoriteErrors.asSharedFlow()

    fun prepareRefresh() = repository.requestPagingRefresh()
    fun selectFilter(filter: RecentDocumentFilter) { savedStateHandle[FILTER_KEY] = filter }
    fun toggleFavorite(document: DocumentFile) {
        if (document.id in _favoriteUpdates.value) return
        _favoriteUpdates.value += document.id
        viewModelScope.launch {
            if (favoritesRepository.toggle(document.id).isFailure) _favoriteErrors.tryEmit(Unit)
            _favoriteUpdates.value -= document.id
        }
    }
    fun onResume() {
        val now = access.isGranted()
        if (now != _uiState.value.hasAccess) {
            _uiState.value = _uiState.value.copy(hasAccess = now)
            if (now) { repository.requestPagingRefresh(); generation.value++ }
        }
    }
    fun dismissPermissionPrompt() { _uiState.value = _uiState.value.copy(permissionPromptDismissed = true) }

    private companion object { const val FILTER_KEY = "recent_document_filter" }
}
