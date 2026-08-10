package com.droidnova.allfilereader.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.domain.model.SafEntry
import com.droidnova.allfilereader.domain.repository.FolderAccessRevokedException
import com.droidnova.allfilereader.domain.repository.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FolderLoadState {
    data object Loading : FolderLoadState
    data class Content(val entries: List<SafEntry>) : FolderLoadState
    data object Empty : FolderLoadState
    data object Error : FolderLoadState
    data object PermissionRevoked : FolderLoadState
}

data class FoldersUiState(
    val loadState: FolderLoadState = FolderLoadState.Loading,
    val currentFolderName: String? = null,
    val isShowingRoots: Boolean = true,
    val persistenceFailed: Boolean = false
)

@HiltViewModel
class FoldersViewModel @Inject constructor(private val repository: FolderRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(FoldersUiState())
    val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()
    private val path = mutableListOf<SafEntry>()
    private var loadJob: Job? = null

    init { loadRoots() }

    fun refresh() { if (path.isEmpty()) loadRoots() else loadChildren(path.last()) }

    fun open(entry: SafEntry) {
        if (!entry.isDirectory) return
        path += entry
        loadChildren(entry)
    }

    fun navigateBack(): Boolean {
        if (path.isEmpty()) return false
        path.removeAt(path.lastIndex)
        if (path.isEmpty()) loadRoots() else loadChildren(path.last())
        return true
    }

    fun addFolder(uri: String) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val persisted = repository.persistReadPermission(uri)
            if (persisted) {
                path.clear()
                try {
                    val roots = repository.persistedFolders()
                    _uiState.value = FoldersUiState(
                        if (roots.isEmpty()) FolderLoadState.Empty else FolderLoadState.Content(roots)
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _uiState.value = FoldersUiState(FolderLoadState.Error)
                }
            } else {
                _uiState.value = _uiState.value.copy(persistenceFailed = true)
            }
        }
    }

    fun dismissPersistenceMessage() {
        _uiState.value = _uiState.value.copy(persistenceFailed = false)
    }

    private fun loadRoots() = launchLoad(showingRoots = true, name = null) { repository.persistedFolders() }

    private fun loadChildren(folder: SafEntry) = launchLoad(showingRoots = false, name = folder.displayName) {
        repository.children(folder.uri)
    }

    private fun launchLoad(showingRoots: Boolean, name: String?, block: suspend () -> List<SafEntry>) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = FoldersUiState(FolderLoadState.Loading, name, showingRoots)
            try {
                val entries = block()
                _uiState.value = FoldersUiState(
                    if (entries.isEmpty()) FolderLoadState.Empty else FolderLoadState.Content(entries),
                    name,
                    showingRoots
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: FolderAccessRevokedException) {
                _uiState.value = FoldersUiState(FolderLoadState.PermissionRevoked, name, showingRoots)
            } catch (_: Exception) {
                _uiState.value = FoldersUiState(FolderLoadState.Error, name, showingRoots)
            }
        }
    }
}
