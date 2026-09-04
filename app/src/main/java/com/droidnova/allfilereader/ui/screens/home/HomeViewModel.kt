package com.droidnova.allfilereader.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

sealed interface HomeCountState {
    data object Loading : HomeCountState
    data class Available(val documents: List<DocumentFile>) : HomeCountState
    data object Unavailable : HomeCountState
}

internal fun availableFavoriteCount(documents: List<DocumentFile>, favoriteIds: Set<String>): Int =
    documents.asSequence().map(DocumentFile::id).distinct().count { it in favoriteIds }

data class HomeUiState(
    val counts: HomeCountState = HomeCountState.Loading,
    val hasAccess: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
    val permissionDismissed: Boolean = false,
    val availableFavoriteCount: Int = 0
) {
    fun count(category: DocumentCategory?): Int? = (counts as? HomeCountState.Available)?.documents?.let { documents ->
        if (category == null) documents.size else documents.count { it.category == category }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val access: MediaPermissionManager,
    favoritesRepository: FavoritesRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(repository.documents, favoritesRepository.favoriteIds) { documents, ids ->
                availableFavoriteCount(documents, ids)
            }.collect { count -> _uiState.value = _uiState.value.copy(availableFavoriteCount = count) }
        }
        load(false)
    }

    fun refresh() = load(true)
    fun dismissPermission() { _uiState.value = _uiState.value.copy(permissionDismissed = true) }
    fun onResume() {
        if (!access.isGranted()) {
            loadJob?.cancel()
            _uiState.value = HomeUiState(counts = HomeCountState.Unavailable, hasAccess = false)
        } else {
            load(true)
        }
    }

    private fun load(force: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val granted = access.isGranted()
            if (!granted) {
                _uiState.value = _uiState.value.copy(
                    counts = if (_uiState.value.counts is HomeCountState.Available) _uiState.value.counts else HomeCountState.Unavailable,
                    hasAccess = false, isRefreshing = false
                )
                return@launch
            }
            val hasCounts = _uiState.value.counts is HomeCountState.Available
            _uiState.value = _uiState.value.copy(
                hasAccess = true,
                counts = if (hasCounts) _uiState.value.counts else HomeCountState.Loading,
                isRefreshing = force && hasCounts,
                hasError = false,
                permissionDismissed = false
            )
            try {
                val documents = repository.getDocuments(force)
                _uiState.value = _uiState.value.copy(
                    counts = HomeCountState.Available(documents),
                    hasAccess = true,
                    isRefreshing = false,
                    hasError = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    counts = if (hasCounts) _uiState.value.counts else HomeCountState.Unavailable,
                    isRefreshing = false,
                    hasError = true
                )
            }
        }
    }
}
