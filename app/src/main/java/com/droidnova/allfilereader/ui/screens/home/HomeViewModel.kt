package com.droidnova.allfilereader.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class HomeTab {
    Recent,
    Bookmarks
}

data class HomeUiState(
    val selectedTab: HomeTab = HomeTab.Recent,
    val recentDocuments: List<DocumentFile> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val permissionManager: MediaPermissionManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.documents.collectLatest { documents ->
                _uiState.update { currentState ->
                    currentState.copy(recentDocuments = documents.take(MAX_RECENT_DOCUMENTS))
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.getDocuments(includeImages = permissionManager.isGranted())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Home retains its empty state; the Files screen offers explicit retry controls.
            }
        }
    }

    fun onTabSelected(tab: HomeTab) {
        _uiState.update { currentState ->
            currentState.copy(selectedTab = tab)
        }
    }

    private companion object {
        const val MAX_RECENT_DOCUMENTS = 10
    }
}
