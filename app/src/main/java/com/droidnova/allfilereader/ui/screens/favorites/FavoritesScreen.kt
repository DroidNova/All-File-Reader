package com.droidnova.allfilereader.ui.screens.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidnova.allfilereader.R

@OptIn(ExperimentalMaterial3Api::class) @Composable fun FavoritesScreen(onBack:()->Unit, viewModel: FavoritesViewModel = viewModel()){val state by viewModel.uiState.collectAsStateWithLifecycle();Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.favorites))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}){pad->PullToRefreshBox(state.isRefreshing,viewModel::refresh,Modifier.fillMaxSize().padding(pad)){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){item{Icon(Icons.Outlined.FavoriteBorder,null,Modifier.size(52.dp));Text(stringResource(R.string.no_favorites_yet),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.no_favorites_supporting_text),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
