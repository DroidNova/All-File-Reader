package com.droidnova.allfilereader.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
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

enum class FileCategory(val id: String) {
    All("all"), Pdf("pdf"), Word("word"), Excel("excel"), PowerPoint("ppt"), Text("txt");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: All }
}

data class CategoryFilesUiState(
    val category: FileCategory,
    val hasAccess: Boolean,
    val permissionPromptDismissed: Boolean = false
)

@HiltViewModel
class CategoryFilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val access: MediaPermissionManager,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {
    private val category = FileCategory.fromId(savedStateHandle["categoryId"])
    private val generation = MutableStateFlow(0)
    val documents: Flow<PagingData<DocumentFile>> = generation.flatMapLatest { repository.pagedDocuments(category.model()) }.cachedIn(viewModelScope)
    private val _uiState = MutableStateFlow(CategoryFilesUiState(category, access.isGranted()))
    val uiState: StateFlow<CategoryFilesUiState> = _uiState.asStateFlow()
    val favoriteIds = favoritesRepository.favoriteIds
    private val _favoriteUpdates = MutableStateFlow<Set<String>>(emptySet())
    val favoriteUpdates: StateFlow<Set<String>> = _favoriteUpdates.asStateFlow()
    private val _favoriteErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val favoriteErrors = _favoriteErrors.asSharedFlow()

    fun prepareRefresh() = repository.requestPagingRefresh()
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
}

private fun FileCategory.model(): DocumentCategory? = when (this) {
    FileCategory.Pdf -> DocumentCategory.Pdf
    FileCategory.Word -> DocumentCategory.Word
    FileCategory.Excel -> DocumentCategory.Excel
    FileCategory.PowerPoint -> DocumentCategory.PowerPoint
    FileCategory.Text -> DocumentCategory.Text
    FileCategory.All -> null
}
