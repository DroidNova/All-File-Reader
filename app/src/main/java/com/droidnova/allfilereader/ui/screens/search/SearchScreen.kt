package com.droidnova.allfilereader.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.ui.components.DocumentFileRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(onBack:()->Unit,onDocumentClick:(DocumentFile)->Unit,viewModel:SearchViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requester=remember{FocusRequester()}; val keyboard=LocalSoftwareKeyboardController.current
    LifecycleResumeEffect(Unit){ viewModel.recheckAccess(); onPauseOrDispose{} }
    LaunchedEffect(Unit){ requester.requestFocus(); keyboard?.show() }
    LaunchedEffect(state.hasAccess){ if(!state.hasAccess) onBack() }
    Scaffold(contentWindowInsets=WindowInsets.safeDrawing,topBar={
        TopAppBar(title={ TextField(value=state.query,onValueChange=viewModel::setQuery,
            modifier=Modifier.fillMaxWidth().focusRequester(requester),singleLine=true,
            label={Text(stringResource(R.string.search_files))},
            trailingIcon={if(state.query.isNotEmpty())IconButton(onClick={viewModel.setQuery("")}){Icon(Icons.Default.Close,stringResource(R.string.clear_search))}},
            colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent)) },
            navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}})
    }){ padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when { state.query.trim().isEmpty()->SearchMessage(R.string.search_your_files,R.string.search_by_file_name)
            state.searching->CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center))
            state.failed->SearchMessage(R.string.search_failed,null)
            state.results.isEmpty()->SearchMessage(R.string.no_files_found,R.string.try_different_file_name)
            else->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=8.dp)){
                items(state.results,key=DocumentFile::id){ document-> DocumentFileRow(document,{onDocumentClick(document)},
                    isFavorite=document.id in state.favoriteIds,onFavoriteToggle={viewModel.toggleFavorite(document)},
                    highlightedName=highlight(document.displayName,state.query.trim())) }
            }
        }
    }}
}

@Composable private fun BoxScope.SearchMessage(title:Int,supporting:Int?){Column(Modifier.align(Alignment.Center).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(stringResource(title),style=MaterialTheme.typography.titleLarge);supporting?.let{Text(stringResource(it),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
private fun highlight(name:String,query:String)=buildAnnotatedString { append(name); val start=name.indexOf(query,ignoreCase=true); if(start>=0)addStyle(SpanStyle(fontWeight=FontWeight.Bold),start,start+query.length) }
