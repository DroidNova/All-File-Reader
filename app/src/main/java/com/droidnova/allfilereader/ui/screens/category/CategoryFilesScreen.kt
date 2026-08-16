package com.droidnova.allfilereader.ui.screens.category

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOff
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

@Composable fun CategoryFilesScreen(onBack:()->Unit,onDocumentClick:(com.droidnova.allfilereader.domain.model.DocumentFile)->Unit,viewModel:CategoryFilesViewModel=hiltViewModel()){val state by viewModel.uiState.collectAsStateWithLifecycle();LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}};val request=rememberStorageAccessRequest(viewModel::onResume);Content(state,onBack,viewModel::refresh,viewModel::retry,request,viewModel::dismissPermissionPrompt,onDocumentClick)}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Content(state:CategoryFilesUiState,onBack:()->Unit,onRefresh:()->Unit,onRetry:()->Unit,onAllow:()->Unit,onNotNow:()->Unit,onDocumentClick:(com.droidnova.allfilereader.domain.model.DocumentFile)->Unit){Scaffold(topBar={TopAppBar(title={Text(stringResource(state.category.title()))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}})}){pad->PullToRefreshBox(state.isRefreshing,onRefresh,Modifier.fillMaxSize().padding(pad)){when(val load=state.loadState){CategoryLoadState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};is CategoryLoadState.Content->LazyColumn(Modifier.fillMaxSize()){items(load.documents,key={it.id}){DocumentFileRow(it,{onDocumentClick(it)})}};CategoryLoadState.Empty->Message(state.category.empty(),R.string.category_empty_supporting_text);CategoryLoadState.Error->Message(R.string.files_error_title,R.string.files_error_supporting_text,onRetry);CategoryLoadState.AccessRequired->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp)){item{PermissionCard(onAllow,onNotNow)}}}}}}
@Composable private fun Message(title:Int,text:Int,action:(()->Unit)?=null){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){item{Icon(Icons.Outlined.FolderOff,null,Modifier.size(52.dp));Text(stringResource(title),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center);Text(stringResource(text),textAlign=TextAlign.Center);action?.let{TextButton(onClick=it){Text(stringResource(R.string.try_again))}}}}}
private fun FileCategory.title()=when(this){FileCategory.All->R.string.all_files;FileCategory.Pdf->R.string.pdf_files;FileCategory.Word->R.string.word_files;FileCategory.Excel->R.string.excel_files;FileCategory.PowerPoint->R.string.ppt_files;FileCategory.Text->R.string.txt_files}
private fun FileCategory.empty()=when(this){FileCategory.All->R.string.no_files_found;FileCategory.Pdf->R.string.no_pdf_files;FileCategory.Word->R.string.no_word_files;FileCategory.Excel->R.string.no_excel_files;FileCategory.PowerPoint->R.string.no_ppt_files;FileCategory.Text->R.string.no_txt_files}
