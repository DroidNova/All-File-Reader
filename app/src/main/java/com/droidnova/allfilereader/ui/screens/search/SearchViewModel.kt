package com.droidnova.allfilereader.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_SEARCH_QUERY_LENGTH = 200
private const val SEARCH_DEBOUNCE_MILLIS = 275L

data class SearchUiState(val query: String = "", val results: List<DocumentFile> = emptyList(),
    val favoriteIds: Set<String> = emptySet(), val searching: Boolean = false, val hasAccess: Boolean = true,
    val failed: Boolean = false)

@HiltViewModel
class SearchViewModel @Inject constructor(private val documents: DocumentRepository,
    private val favorites: FavoritesRepository, private val permission: MediaPermissionManager) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init { viewModelScope.launch {
        combine(query, documents.documents, favorites.favoriteIds) { q, docs, ids -> Triple(q, docs, ids) }
            .collectLatest { (raw, docs, ids) ->
                val access = permission.isGranted()
                if (!access) { _state.value = SearchUiState(query = raw, hasAccess = false); return@collectLatest }
                val needle = normalize(raw.trim())
                if (needle.isEmpty()) { _state.value = SearchUiState(query = raw, favoriteIds = ids); return@collectLatest }
                _state.update { it.copy(query = raw, favoriteIds = ids, searching = true, failed = false) }
                delay(SEARCH_DEBOUNCE_MILLIS)
                val found = withContext(Dispatchers.Default) { rank(docs, needle) }
                _state.value = SearchUiState(raw, found, ids, hasAccess = true)
            }
    } }
    fun setQuery(value: String) { query.value = value.take(MAX_SEARCH_QUERY_LENGTH); if (value.isEmpty()) _state.update { it.copy(query="", results=emptyList(), searching=false) } }
    fun toggleFavorite(document: DocumentFile) = viewModelScope.launch { favorites.toggle(document.id) }
    fun recheckAccess() { if (!permission.isGranted()) { documents.clearSnapshots(); _state.value = SearchUiState(hasAccess=false) } }

    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        fun rank(source: List<DocumentFile>, needle: String): List<DocumentFile> {
            if (needle.isEmpty()) return emptyList()
            return source.asSequence().mapNotNull { file ->
            val name = normalize(file.displayName)
            val score = when { name == needle -> 0; name.startsWith(needle) -> 1
                tokenStarts(name, needle) -> 2; name.contains(needle) -> 3; else -> return@mapNotNull null }
            Triple(file, score, name)
        }.sortedWith(compareBy<Triple<DocumentFile,Int,String>> { it.second }
            .thenByDescending { it.first.lastModifiedEpochMillis }.thenBy { it.third }.thenBy { it.first.id })
            .map { it.first }.toList()
        }
        private fun tokenStarts(name: String, query: String): Boolean = name.indices.any { index ->
            (index == 0 || !name[index - 1].isLetterOrDigit()) && name.startsWith(query, index)
        }
    }
}
