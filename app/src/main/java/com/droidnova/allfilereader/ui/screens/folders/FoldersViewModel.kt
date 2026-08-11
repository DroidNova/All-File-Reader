package com.droidnova.allfilereader.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
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

sealed interface FolderLoadState{data object Loading:FolderLoadState;data class Content(val entries:List<SafEntry>):FolderLoadState;data object Empty:FolderLoadState;data object Error:FolderLoadState;data object AccessRequired:FolderLoadState}
data class FoldersUiState(val loadState:FolderLoadState=FolderLoadState.Loading,val currentFolderName:String?=null,val isShowingRoots:Boolean=true,val isRefreshing:Boolean=false)
@HiltViewModel class FoldersViewModel @Inject constructor(private val repository:FolderRepository,private val access:MediaPermissionManager):ViewModel(){
 private val _uiState=MutableStateFlow(FoldersUiState());val uiState:StateFlow<FoldersUiState> =_uiState.asStateFlow();private val path=mutableListOf<SafEntry>();private var root:SafEntry?=null;private var job:Job?=null
 init{load(false)};fun refresh()=load(true);fun onResume(){val granted=access.isGranted();if(granted != (_uiState.value.loadState !is FolderLoadState.AccessRequired))load(true)}
 fun open(entry:SafEntry){if(!entry.isDirectory)return;if(root==null)root=entry;path+=entry;load(false)}
 fun navigateBack():Boolean{if(path.isEmpty())return false;path.removeAt(path.lastIndex);if(path.isEmpty())root=null;load(false);return true}
 private fun load(refresh:Boolean){if(job?.isActive==true)return;job=viewModelScope.launch{if(!access.isGranted()){_uiState.value=FoldersUiState(FolderLoadState.AccessRequired);return@launch};val old=(_uiState.value.loadState as? FolderLoadState.Content)?.entries;val name=path.lastOrNull()?.displayName;_uiState.value=if(old!=null)FoldersUiState(FolderLoadState.Content(old),name,path.isEmpty(),refresh)else FoldersUiState(currentFolderName=name,isShowingRoots=path.isEmpty());try{val list=if(path.isEmpty())repository.roots()else repository.children(root!!.uri,path.last().uri);_uiState.value=FoldersUiState(if(list.isEmpty())FolderLoadState.Empty else FolderLoadState.Content(list),name,path.isEmpty())}catch(c:CancellationException){throw c}catch(_:FolderAccessRevokedException){_uiState.value=FoldersUiState(FolderLoadState.Error,name,path.isEmpty())}catch(_:Exception){_uiState.value=FoldersUiState(FolderLoadState.Error,name,path.isEmpty())}}}
}
