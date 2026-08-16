package com.droidnova.allfilereader.ui.screens.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.ui.components.DocumentFileRow
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import com.droidnova.allfilereader.ui.screens.home.PermissionCard

@Composable fun FilesScreen(onDocumentClick:(com.droidnova.allfilereader.domain.model.DocumentFile)->Unit,viewModel:FilesViewModel=hiltViewModel()) { val state by viewModel.uiState.collectAsStateWithLifecycle();LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}};val request=rememberStorageAccessRequest(viewModel::onResume);RecentContent(state,viewModel::refresh,viewModel::retry,request,viewModel::dismissPermissionPrompt,onDocumentClick) }
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RecentContent(state:FilesUiState,onRefresh:()->Unit,onRetry:()->Unit,onAllow:()->Unit,onNotNow:()->Unit,onDocumentClick:(com.droidnova.allfilereader.domain.model.DocumentFile)->Unit){Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={TopAppBar(title={Text(stringResource(R.string.recent))})}){pad->PullToRefreshBox(state.isRefreshing,onRefresh,Modifier.fillMaxSize().padding(pad)){when(val load=state.loadState){FilesLoadState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};is FilesLoadState.Content->LazyColumn(Modifier.fillMaxSize()){items(load.documents,key={it.id}){DocumentFileRow(it,{onDocumentClick(it)})}};FilesLoadState.Empty->Message(R.string.no_recent_files,R.string.no_recent_files_supporting_text);FilesLoadState.Error->Message(R.string.files_error_title,R.string.files_error_supporting_text,onRetry);FilesLoadState.AccessRequired->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp)){item{PermissionCard(onAllow,onNotNow)}}}}}}
@Composable private fun Message(title:Int,text:Int,action:(()->Unit)?=null){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){item{Icon(Icons.Outlined.History,null,Modifier.size(52.dp));Text(stringResource(title),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center);Text(stringResource(text),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant);action?.let{TextButton(onClick=it){Text(stringResource(R.string.try_again))}}}}}
