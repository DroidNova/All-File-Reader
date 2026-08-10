package com.droidnova.allfilereader.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.permission.RequiredMediaPermission
import com.droidnova.allfilereader.domain.model.DocumentCategory
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

enum class FileCategory(val id: String) {
    All("all"), Pdf("pdf"), Word("word"), Excel("excel"), PowerPoint("powerpoint"),
    Text("text"), Images("images");

    companion object {
        fun fromId(id: String?): FileCategory = entries.firstOrNull { it.id == id } ?: All
    }
}

sealed interface CategoryLoadState {
    data object Loading : CategoryLoadState
    data class Content(val documents: List<DocumentFile>) : CategoryLoadState
    data object Empty : CategoryLoadState
    data object Error : CategoryLoadState
}

data class CategoryFilesUiState(
    val category: FileCategory,
    val loadState: CategoryLoadState = CategoryLoadState.Loading,
    val requiredPermission: RequiredMediaPermission? = null,
    val permissionPromptDismissed: Boolean = false
)

@HiltViewModel
class CategoryFilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager
) : ViewModel() {
    private val category = FileCategory.fromId(savedStateHandle["categoryId"])
    private val _uiState = MutableStateFlow(CategoryFilesUiState(category))
    val uiState: StateFlow<CategoryFilesUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { loadFiles() }

    fun refresh() = loadFiles(forceRefresh = true)
    fun retry() = loadFiles(forceRefresh = true)

    fun onPermissionResult(granted: Boolean) {
        if (granted) loadFiles(forceRefresh = true)
        else _uiState.value = _uiState.value.copy(permissionPromptDismissed = true)
    }

    fun dismissPermissionPrompt() {
        _uiState.value = _uiState.value.copy(permissionPromptDismissed = true)
    }

    private fun loadFiles(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val permissionGranted = permissionManager.isGranted()
            val needsImages = category == FileCategory.Images || category == FileCategory.All
            val requiredPermission = permissionManager.requiredPermission().takeIf {
                !permissionGranted && (needsImages || !permissionManager.canQueryDocumentsWithoutPermission())
            }
            if (!permissionGranted && !permissionManager.canQueryDocumentsWithoutPermission()) {
                _uiState.value = CategoryFilesUiState(category, CategoryLoadState.Empty, requiredPermission)
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                loadState = CategoryLoadState.Loading,
                requiredPermission = requiredPermission,
                permissionPromptDismissed = false
            )
            try {
                val documents = repository.getDocuments(
                    includeImages = needsImages && permissionGranted,
                    forceRefresh = forceRefresh
                ).filter { document ->
                    when (category) {
                        FileCategory.All -> true
                        FileCategory.Pdf -> document.category == DocumentCategory.Pdf
                        FileCategory.Word -> document.category == DocumentCategory.Word
                        FileCategory.Excel -> document.category == DocumentCategory.Excel
                        FileCategory.PowerPoint -> document.category == DocumentCategory.PowerPoint
                        FileCategory.Text -> document.category == DocumentCategory.Text
                        FileCategory.Images -> document.category == DocumentCategory.Image
                    }
                }.sortedByDescending(DocumentFile::lastModifiedEpochMillis)
                _uiState.value = CategoryFilesUiState(
                    category = category,
                    loadState = if (documents.isEmpty()) CategoryLoadState.Empty
                    else CategoryLoadState.Content(documents),
                    requiredPermission = requiredPermission
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = CategoryFilesUiState(category, CategoryLoadState.Error, requiredPermission)
            }
        }
    }
}
