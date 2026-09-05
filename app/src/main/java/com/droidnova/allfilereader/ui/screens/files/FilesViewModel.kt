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
import com.droidnova.allfilereader.navigation.RecentSearchCoordinator
import com.droidnova.allfilereader.navigation.SearchActivationRequest
import com.droidnova.allfilereader.navigation.SearchActivationSource
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
    fun matches(document:DocumentFile):Boolean { val classified=DocumentClassifier.classificationOf(document);return DocumentClassifier.isVisibleDocument(classified)&&(category==null||classified.category==category) }
}

data class FilesUiState(val hasAccess:Boolean,val permissionPromptDismissed:Boolean=false,
    val searchActive:Boolean=false,val query:String="",val results:List<DocumentFile> = emptyList(),
    val searching:Boolean=false,val searchFailed:Boolean=false,val refreshing:Boolean=false,
    val focusRequestId:Long?=null,val categoryResetRequestId:Long?=null)

internal data class SearchActivationChange(
    val state: FilesUiState,
    val selectedFilter: RecentDocumentFilter
)

internal fun applySearchActivation(
    current: FilesUiState,
    selectedFilter: RecentDocumentFilter,
    lastHandledRequestId: Long?,
    request: SearchActivationRequest
): SearchActivationChange? {
    if (lastHandledRequestId == request.id) return null
    return SearchActivationChange(
        state = current.copy(
            searchActive = true,
            query = if (request.clearQuery) "" else current.query,
            searching = false,
            focusRequestId = request.id,
            categoryResetRequestId = request.id.takeIf { request.resetCategoryToAll }
        ),
        selectedFilter = if (request.resetCategoryToAll) RecentDocumentFilter.All else selectedFilter
    )
}

@HiltViewModel class FilesViewModel @Inject constructor(private val repository:DocumentRepository,
    private val access:MediaPermissionManager,private val favoritesRepository:FavoritesRepository,
    private val saved:SavedStateHandle,private val searchCoordinator:RecentSearchCoordinator):ViewModel(){
    val selectedFilter=saved.getStateFlow(FILTER_KEY,RecentDocumentFilter.All)
    private val _state=MutableStateFlow(FilesUiState(access.isGranted(),searchActive=saved[ACTIVE_KEY]?:false,query=saved[QUERY_KEY]?:""))
    val uiState=_state.asStateFlow(); val favoriteIds=favoritesRepository.favoriteIds
    private val _favoriteUpdates=MutableStateFlow<Set<String>>(emptySet());val favoriteUpdates=_favoriteUpdates.asStateFlow()
    private val _favoriteErrors=MutableSharedFlow<Unit>(extraBufferCapacity=1);val favoriteErrors=_favoriteErrors.asSharedFlow()

    init { val debounced=_state.map{it.query}.distinctUntilChanged().transformLatest { value->val normalized=FilenameSearch.query(value);if(normalized.isNotEmpty())delay(275);emit(normalized) }
        viewModelScope.launch { combine(repository.documents,selectedFilter,debounced){docs,filter,q->Triple(docs,filter,q)}
            .collectLatest { (docs,filter,q)->
                if(!access.isGranted()){repository.clearSnapshots();_state.value=FilesUiState(false);return@collectLatest}
                val visible=withContext(Dispatchers.Default){FilenameSearch.search(docs,q).filter(filter::matches)}
                _state.update{it.copy(results=visible,searching=false,searchFailed=false)} }
        }
        viewModelScope.launch { searchCoordinator.pending.collect { request -> request?.let {
            handleSearchActivation(it);searchCoordinator.acknowledge(it.id)
        }
        } }
    }
    fun requestSearchFromRecent(){searchCoordinator.request(SearchActivationSource.Recent)}
    fun handleSearchActivation(request:SearchActivationRequest){
        val change=applySearchActivation(_state.value,selectedFilter.value,saved[LAST_ACTIVATION_KEY],request)?:return
        saved[LAST_ACTIVATION_KEY]=request.id
        saved[FILTER_KEY]=change.selectedFilter
        saved[ACTIVE_KEY]=true
        if(request.clearQuery)saved[QUERY_KEY]=""
        _state.value=change.state
    }
    fun onCategoryResetApplied(id:Long){_state.update{if(it.categoryResetRequestId==id)it.copy(categoryResetRequestId=null)else it}}
    fun onFocusRequestHandled(id:Long){_state.update{if(it.focusRequestId==id)it.copy(focusRequestId=null)else it}}
    fun exitSearch(){saved[ACTIVE_KEY]=false;saved[QUERY_KEY]="";_state.update{it.copy(searchActive=false,query="",focusRequestId=null,searching=false)}}
    fun setQuery(value:String){val bounded=value.take(FilenameSearch.MAX_QUERY_LENGTH);saved[QUERY_KEY]=bounded;_state.update{if(it.query==bounded)it else it.copy(query=bounded,searching=bounded.trim().isNotEmpty())}}
    fun selectFilter(filter:RecentDocumentFilter){saved[FILTER_KEY]=filter}
    fun refresh(){if(_state.value.refreshing)return;_state.update{it.copy(refreshing=true)};viewModelScope.launch{try{repository.getDocuments(true)}catch(c:CancellationException){throw c}catch(_:Exception){_state.update{it.copy(searchFailed=it.searchActive)}}finally{_state.update{it.copy(refreshing=false)}}}}
    fun toggleFavorite(document:DocumentFile){if(document.id in _favoriteUpdates.value)return;_favoriteUpdates.value+=document.id;viewModelScope.launch{if(favoritesRepository.toggle(document.id).isFailure)_favoriteErrors.tryEmit(Unit);_favoriteUpdates.value-=document.id}}
    fun onResume(){val granted=access.isGranted();if(!granted)repository.clearSnapshots();_state.update{it.copy(hasAccess=granted,results=if(granted)it.results else emptyList())};if(granted&&repository.documents.value.isEmpty())viewModelScope.launch{runCatching{repository.getDocuments(false)}}}
    fun dismissPermissionPrompt(){_state.update{it.copy(permissionPromptDismissed=true)}}
    companion object{private const val FILTER_KEY="recent_document_filter";private const val QUERY_KEY="recent_search_query";private const val ACTIVE_KEY="recent_search_active";private const val LAST_ACTIVATION_KEY="recent_last_activation_id"}
}
