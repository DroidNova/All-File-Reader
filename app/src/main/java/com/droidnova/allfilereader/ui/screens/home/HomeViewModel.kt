package com.droidnova.allfilereader.ui.screens.home

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

data class HomeUiState(val documents: List<DocumentFile> = emptyList(), val hasAccess:Boolean=false, val isLoading:Boolean=true, val isRefreshing:Boolean=false, val hasError:Boolean=false, val permissionDismissed:Boolean=false) {
    fun count(category: DocumentCategory?) = if(category==null) documents.size else documents.count { it.category==category }
}
@HiltViewModel
class HomeViewModel @Inject constructor(private val repository:DocumentRepository,private val access:MediaPermissionManager):ViewModel(){
 private val _uiState=MutableStateFlow(HomeUiState());val uiState:StateFlow<HomeUiState> =_uiState.asStateFlow();private var job:Job?=null
 init{load(false)};fun refresh()=load(true);fun dismissPermission(){_uiState.value=_uiState.value.copy(permissionDismissed=true)}
 fun onResume(){val granted=access.isGranted();if(granted!=_uiState.value.hasAccess)load(true)}
 private fun load(force:Boolean){if(job?.isActive==true)return;job=viewModelScope.launch{val granted=access.isGranted();if(!granted){_uiState.value=_uiState.value.copy(hasAccess=false,isLoading=false,isRefreshing=false);return@launch};val old=_uiState.value.documents;_uiState.value=_uiState.value.copy(hasAccess=true,isLoading=old.isEmpty(),isRefreshing=force&&old.isNotEmpty(),hasError=false,permissionDismissed=false);try{val docs=repository.getDocuments(force);_uiState.value=HomeUiState(docs,true)}catch(c:CancellationException){throw c}catch(_:Exception){_uiState.value=_uiState.value.copy(isLoading=false,isRefreshing=false,hasError=true)}}}
}
