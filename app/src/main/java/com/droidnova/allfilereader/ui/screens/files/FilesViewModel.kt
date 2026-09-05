package com.droidnova.allfilereader.ui.screens.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import com.droidnova.allfilereader.domain.search.FilenameSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecentDocumentFilter(val category: DocumentCategory?) {
    All(null), Pdf(DocumentCategory.Pdf), Word(DocumentCategory.Word), Excel(DocumentCategory.Excel),
    PowerPoint(DocumentCategory.PowerPoint), Text(DocumentCategory.Text);
    fun matches(document:DocumentFile):Boolean { val classified=DocumentClassifier.classify(document.mimeType,document.extension?:DocumentClassifier.extensionOf(document.displayName));return DocumentClassifier.isVisibleDocument(classified)&&(category==null||classified==category) }
}

data class FilesUiState(val hasAccess:Boolean,val permissionPromptDismissed:Boolean=false,
    val searchActive:Boolean=false,val query:String="",val results:List<DocumentFile> = emptyList(),
    val searching:Boolean=false,val searchFailed:Boolean=false,val refreshing:Boolean=false)

@HiltViewModel class FilesViewModel @Inject constructor(private val repository:DocumentRepository,
    private val access:MediaPermissionManager,private val favoritesRepository:FavoritesRepository,
    private val saved:SavedStateHandle):ViewModel(){
    val selectedFilter=saved.getStateFlow(FILTER_KEY,RecentDocumentFilter.All)
    private val query=saved.getStateFlow(QUERY_KEY,"")
    private val searchActive=saved.getStateFlow(ACTIVE_KEY,false)
    private val _state=MutableStateFlow(FilesUiState(access.isGranted()))
    val uiState=_state.asStateFlow(); val favoriteIds=favoritesRepository.favoriteIds
    private val _favoriteUpdates=MutableStateFlow<Set<String>>(emptySet());val favoriteUpdates=_favoriteUpdates.asStateFlow()
    private val _favoriteErrors=MutableSharedFlow<Unit>(extraBufferCapacity=1);val favoriteErrors=_favoriteErrors.asSharedFlow()

    init { val debounced=query.transformLatest { value->if(value.isNotBlank())delay(275);emit(FilenameSearch.query(value)) }
        viewModelScope.launch { combine(repository.documents,selectedFilter,debounced,searchActive){docs,filter,q,active->Array<Any>(4){when(it){0->docs;1->filter;2->q;else->active}}}
            .collectLatest { values->@Suppress("UNCHECKED_CAST") val docs=values[0] as List<DocumentFile>;val filter=values[1] as RecentDocumentFilter;val q=values[2] as String;val active=values[3] as Boolean
                if(!access.isGranted()){repository.clearSnapshots();_state.value=FilesUiState(false);return@collectLatest}
                _state.update{it.copy(searchActive=active,query=query.value,searching=active&&q.isNotEmpty(),searchFailed=false)}
                val visible=withContext(Dispatchers.Default){FilenameSearch.search(docs,q).filter(filter::matches)}
                _state.update{it.copy(results=visible,searching=false,query=query.value,searchActive=active)} }
        }
        viewModelScope.launch { saved.getStateFlow(ACTIVATE_KEY,false).collect { requested ->
            if(requested){activateSearch();saved[ACTIVATE_KEY]=false}
        } }
    }
    fun activateSearch(){saved[FILTER_KEY]=RecentDocumentFilter.All;saved[QUERY_KEY]="";saved[ACTIVE_KEY]=true}
    fun exitSearch(){saved[ACTIVE_KEY]=false;saved[QUERY_KEY]=""}
    fun setQuery(value:String){saved[QUERY_KEY]=value.take(FilenameSearch.MAX_QUERY_LENGTH);if(value.isEmpty())_state.update{it.copy(query="",searching=false)}}
    fun selectFilter(filter:RecentDocumentFilter){saved[FILTER_KEY]=filter}
    fun refresh(){if(_state.value.refreshing)return;_state.update{it.copy(refreshing=true)};viewModelScope.launch{try{repository.getDocuments(true)}catch(c:CancellationException){throw c}catch(_:Exception){_state.update{it.copy(searchFailed=it.searchActive)}}finally{_state.update{it.copy(refreshing=false)}}}}
    fun toggleFavorite(document:DocumentFile){if(document.id in _favoriteUpdates.value)return;_favoriteUpdates.value+=document.id;viewModelScope.launch{if(favoritesRepository.toggle(document.id).isFailure)_favoriteErrors.tryEmit(Unit);_favoriteUpdates.value-=document.id}}
    fun onResume(){val granted=access.isGranted();if(!granted)repository.clearSnapshots();_state.update{it.copy(hasAccess=granted,results=if(granted)it.results else emptyList())};if(granted&&repository.documents.value.isEmpty())refresh()}
    fun dismissPermissionPrompt(){_state.update{it.copy(permissionPromptDismissed=true)}}
    companion object{const val ACTIVATE_KEY="activate_recent_search";private const val FILTER_KEY="recent_document_filter";private const val QUERY_KEY="recent_search_query";private const val ACTIVE_KEY="recent_search_active"}
}
