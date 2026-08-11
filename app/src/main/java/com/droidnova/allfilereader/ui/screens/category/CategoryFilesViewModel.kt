package com.droidnova.allfilereader.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
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

enum class FileCategory(val id: String) { All("all"), Pdf("pdf"), Word("word"), Excel("excel"), PowerPoint("ppt"), Text("txt"); companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: All } }
sealed interface CategoryLoadState { data object Loading: CategoryLoadState; data class Content(val documents: List<DocumentFile>): CategoryLoadState; data object Empty: CategoryLoadState; data object AccessRequired: CategoryLoadState; data object Error: CategoryLoadState }
data class CategoryFilesUiState(val category: FileCategory, val loadState: CategoryLoadState = CategoryLoadState.Loading, val isRefreshing: Boolean = false, val permissionPromptDismissed: Boolean = false)

@HiltViewModel
class CategoryFilesViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repository: DocumentRepository, private val access: MediaPermissionManager): ViewModel() {
    private val category = FileCategory.fromId(savedStateHandle["categoryId"])
    private val _uiState = MutableStateFlow(CategoryFilesUiState(category)); val uiState: StateFlow<CategoryFilesUiState> = _uiState.asStateFlow(); private var job: Job? = null
    init { load(false) }
    fun refresh()=load(true); fun retry()=load(true)
    fun onResume() { val granted = access.isGranted(); if (granted != (_uiState.value.loadState !is CategoryLoadState.AccessRequired)) load(true) }
    fun dismissPermissionPrompt(){ _uiState.value=_uiState.value.copy(permissionPromptDismissed=true) }
    private fun load(force:Boolean){ if(job?.isActive==true)return; job=viewModelScope.launch {
        if(!access.isGranted()){_uiState.value=CategoryFilesUiState(category,CategoryLoadState.AccessRequired,permissionPromptDismissed=_uiState.value.permissionPromptDismissed);return@launch}
        val old=(_uiState.value.loadState as? CategoryLoadState.Content)?.documents; _uiState.value=if(old==null) CategoryFilesUiState(category) else CategoryFilesUiState(category,CategoryLoadState.Content(old),force)
        try { val docs=repository.getDocuments(force).filter { category==FileCategory.All || it.category==category.model() }; _uiState.value=CategoryFilesUiState(category,if(docs.isEmpty())CategoryLoadState.Empty else CategoryLoadState.Content(docs)) }
        catch(c:CancellationException){throw c}catch(_:Exception){_uiState.value=CategoryFilesUiState(category,if(old==null)CategoryLoadState.Error else CategoryLoadState.Content(old))}
    }}
}
private fun FileCategory.model()=when(this){FileCategory.Pdf->DocumentCategory.Pdf;FileCategory.Word->DocumentCategory.Word;FileCategory.Excel->DocumentCategory.Excel;FileCategory.PowerPoint->DocumentCategory.PowerPoint;FileCategory.Text->DocumentCategory.Text;FileCategory.All->DocumentCategory.Other}
