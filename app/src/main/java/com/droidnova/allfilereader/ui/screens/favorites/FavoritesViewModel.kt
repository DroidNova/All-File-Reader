package com.droidnova.allfilereader.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

data class FavoritesUiState(
    val documents: List<DocumentFile> = emptyList(),
    val savedFavoriteCount: Int = 0,
    val isRefreshing: Boolean = false,
    val updatesInProgress: Set<String> = emptySet()
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val documentsRepository: DocumentRepository,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
    private val _favoriteErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val favoriteErrors = _favoriteErrors.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(documentsRepository.documents, favoritesRepository.favoriteIds) { documents, ids ->
                ids to documents.filter { it.id in ids && DocumentClassifier.isVisibleDocument(it) }.distinctBy(DocumentFile::id)
            }.collect { (ids, available) ->
                _uiState.value = _uiState.value.copy(documents = available, savedFavoriteCount = available.size)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                documentsRepository.getDocuments(forceRefresh = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Existing document screens own file-access errors; favorite IDs remain persisted.
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun removeFavorite(document: DocumentFile) {
        if (document.id in _uiState.value.updatesInProgress) return
        _uiState.value = _uiState.value.copy(updatesInProgress = _uiState.value.updatesInProgress + document.id)
        viewModelScope.launch {
            if (favoritesRepository.remove(document.id).isFailure) _favoriteErrors.tryEmit(Unit)
            _uiState.value = _uiState.value.copy(updatesInProgress = _uiState.value.updatesInProgress - document.id)
        }
    }
}
