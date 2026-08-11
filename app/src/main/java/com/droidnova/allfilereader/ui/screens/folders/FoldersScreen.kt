package com.droidnova.allfilereader.ui.screens.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.*
import com.droidnova.allfilereader.ui.components.DocumentFileRow
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import com.droidnova.allfilereader.ui.screens.home.PermissionCard

@Composable fun FoldersScreen(onNavigateHome:()->Unit,onDocumentClick:(DocumentFile)->Unit,viewModel:FoldersViewModel=hiltViewModel()){val state by viewModel.uiState.collectAsStateWithLifecycle();LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}};val back={if(!viewModel.navigateBack())onNavigateHome()};BackHandler(onBack=back);val request=rememberStorageAccessRequest(viewModel::onResume);Content(state,back,viewModel::refresh,viewModel::open,request,onDocumentClick)}
@OptIn(ExperimentalMaterial3Api::class) @Composable private fun Content(state:FoldersUiState,onBack:()->Unit,onRefresh:()->Unit,onOpen:(SafEntry)->Unit,onAllow:()->Unit,onDocumentClick:(DocumentFile)->Unit){Scaffold(topBar={TopAppBar(title={Text(state.currentFolderName?:stringResource(R.string.directories),maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}})}){pad->PullToRefreshBox(state.isRefreshing,onRefresh,Modifier.fillMaxSize().padding(pad)){when(val load=state.loadState){FolderLoadState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};is FolderLoadState.Content->LazyColumn(Modifier.fillMaxSize()){items(load.entries,key={it.id}){e->if(e.isDirectory)FolderRow(e,state.isShowingRoots){onOpen(e)}else DocumentFileRow(e.asDocument(),{onDocumentClick(e.asDocument())})}};FolderLoadState.Empty->Message(R.string.folder_is_empty,R.string.folder_is_empty_supporting_text);FolderLoadState.Error->Message(R.string.folder_error_title,R.string.directory_access_denied,onRefresh);FolderLoadState.AccessRequired->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp)){item{PermissionCard(onAllow,{})}}}}}}
@Composable private fun FolderRow(e:SafEntry,root:Boolean,onClick:()->Unit){Surface(onClick=onClick,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Folder,null,Modifier.size(40.dp));Spacer(Modifier.width(16.dp));Column{Text(e.displayName,maxLines=1,overflow=TextOverflow.Ellipsis);Text(stringResource(if(root)R.string.storage_root else R.string.folder),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
@Composable private fun Message(title:Int,text:Int,action:(()->Unit)?=null){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){item{Icon(Icons.Outlined.FolderOff,null,Modifier.size(52.dp));Text(stringResource(title),style=MaterialTheme.typography.titleMedium);Text(stringResource(text));action?.let{TextButton(onClick=it){Text(stringResource(R.string.try_again))}}}}}
private fun SafEntry.asDocument():DocumentFile{val ext=DocumentClassifier.extensionOf(displayName);return DocumentFile(DocumentIds.fromStorageLocation(uri),displayName,java.io.File(uri).toURI().toString(),mimeType,ext,sizeBytes?:-1,lastModifiedEpochMillis?:0,DocumentClassifier.classify(mimeType,ext),false)}
