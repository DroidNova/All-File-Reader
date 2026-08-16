package com.droidnova.allfilereader.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.SafEntry
import com.droidnova.allfilereader.domain.repository.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*

data class FoldersUiState(val currentFolderName: String? = null, val isShowingRoots: Boolean = true, val hasAccess: Boolean)
private data class DirectoryLocation(val root: SafEntry? = null, val path: List<SafEntry> = emptyList(), val generation: Int = 0)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: FolderRepository,
    private val access: MediaPermissionManager
) : ViewModel() {
    private val location = MutableStateFlow(DirectoryLocation())
    private val granted = MutableStateFlow(access.isGranted())
    val uiState: StateFlow<FoldersUiState> = combine(location, granted) { place, hasAccess ->
        FoldersUiState(place.path.lastOrNull()?.displayName, place.path.isEmpty(), hasAccess)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState(hasAccess = access.isGranted()))
    val entries: Flow<PagingData<SafEntry>> = location.flatMapLatest { place ->
        repository.pagedEntries(place.root?.uri, place.path.lastOrNull()?.uri)
    }.cachedIn(viewModelScope)

    fun onResume() {
        val now = access.isGranted()
        if (now != granted.value) {
            granted.value = now
            location.value = location.value.copy(generation = location.value.generation + 1)
        }
    }

    fun open(entry: SafEntry) {
        if (!entry.isDirectory) return
        val current = location.value
        location.value = current.copy(root = current.root ?: entry, path = current.path + entry)
    }

    fun navigateBack(): Boolean {
        val current = location.value
        if (current.path.isEmpty()) return false
        val parent = current.path.dropLast(1)
        location.value = current.copy(root = if (parent.isEmpty()) null else current.root, path = parent)
        return true
    }
}
