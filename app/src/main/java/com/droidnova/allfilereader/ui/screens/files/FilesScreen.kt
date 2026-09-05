package com.droidnova.allfilereader.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.withFrameNanos
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.search.FilenameSearch
import com.droidnova.allfilereader.ui.components.DocumentFileRow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun FilesScreen(onDocumentClick:(DocumentFile)->Unit,viewModel:FilesViewModel=hiltViewModel()){
 val state by viewModel.uiState.collectAsStateWithLifecycle();val selected by viewModel.selectedFilter.collectAsStateWithLifecycle()
 val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle(emptySet());val updates by viewModel.favoriteUpdates.collectAsStateWithLifecycle()
 val pager=rememberPagerState(initialPage=selected.ordinal,pageCount={RecentDocumentFilter.entries.size});val scope=rememberCoroutineScope()
 val keyboard=LocalSoftwareKeyboardController.current;val focus=remember{FocusRequester()};val snackbar=remember{SnackbarHostState()}
 val favoriteError=stringResource(R.string.favorites_update_failed)
 LaunchedEffect(Unit){viewModel.favoriteErrors.collect{snackbar.showSnackbar(favoriteError)}}
 LaunchedEffect(pager.settledPage){val filter=RecentDocumentFilter.entries[pager.settledPage];if(filter!=selected)viewModel.selectFilter(filter)}
 LaunchedEffect(selected){if(pager.currentPage!=selected.ordinal)pager.animateScrollToPage(selected.ordinal)}
 LaunchedEffect(state.searchActive,state.focusRequestId){val id=state.focusRequestId;if(state.searchActive&&id!=null){withFrameNanos{};focus.requestFocus();keyboard?.show();viewModel.onFocusRequestHandled(id)}}
 LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}}
 BackHandler(state.searchActive){keyboard?.hide();viewModel.exitSearch()}
 Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)},topBar={Column{
   if(state.searchActive)TopAppBar(title={TextField(state.query,viewModel::setQuery,Modifier.fillMaxWidth().focusRequester(focus).testTag("recent_search_field"),
      placeholder={Text(stringResource(R.string.search_files))},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
      keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),
      trailingIcon={if(state.query.isNotEmpty())IconButton(onClick={viewModel.setQuery("")}){Icon(Icons.Default.Close,stringResource(R.string.clear_search))}},
      colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent))},
      navigationIcon={IconButton(onClick={keyboard?.hide();viewModel.exitSearch()}){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}})
   else TopAppBar(title={Text(stringResource(R.string.recent))},actions={IconButton(onClick=viewModel::requestSearchFromRecent){Icon(Icons.Default.Search,stringResource(R.string.search_files))}})
   RecentFilterRow(pager.currentPage){scope.launch{pager.animateScrollToPage(it)}}
 }}){padding->HorizontalPager(pager,Modifier.fillMaxSize().padding(padding),key={RecentDocumentFilter.entries[it].name}){page->
   val filter=RecentDocumentFilter.entries[page];val pageDocuments=remember(state.results,filter){state.results.filter(filter::matches)}
   val listState=rememberLazyListState();LaunchedEffect(state.query){listState.scrollToItem(0)}
   PullToRefreshBox(state.refreshing,viewModel::refresh,Modifier.fillMaxSize()){
    when{!state.hasAccess->Box(Modifier.fillMaxSize());state.searchFailed->RecentMessage(R.string.search_failed,null)
     state.searching->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp))}
     pageDocuments.isEmpty()->RecentMessage(emptyTitle(filter,state.searchActive),if(state.searchActive&&state.query.isNotBlank())R.string.try_different_file_name else R.string.no_recent_files_supporting_text)
     else->LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(vertical=8.dp)){items(pageDocuments,key=DocumentFile::id){doc->
       DocumentFileRow(doc,{onDocumentClick(doc)},doc.id in favorites,doc.id !in updates,{viewModel.toggleFavorite(doc)},
         highlightedName=if(state.searchActive&&state.query.isNotBlank())highlightFilename(doc.displayName,state.query)else null)
     }}
    }
   }
 }} }

@Composable private fun RecentFilterRow(selected:Int,onSelected:(Int)->Unit){val labels=listOf(R.string.category_all,R.string.pdf,R.string.word,R.string.excel,R.string.ppt,R.string.txt);ScrollableTabRow(selected,edgePadding=0.dp,divider={}){RecentDocumentFilter.entries.indices.forEach{index->Tab(index==selected,{onSelected(index)},text={Text(stringResource(labels[index]))})}}}
@StringRes private fun emptyTitle(filter:RecentDocumentFilter,search:Boolean)=if(!search)when(filter){RecentDocumentFilter.All->R.string.no_recent_files;RecentDocumentFilter.Pdf->R.string.no_recent_pdf_files;RecentDocumentFilter.Word->R.string.no_recent_word_files;RecentDocumentFilter.Excel->R.string.no_recent_excel_files;RecentDocumentFilter.PowerPoint->R.string.no_recent_powerpoint_files;RecentDocumentFilter.Text->R.string.no_recent_text_files}else when(filter){RecentDocumentFilter.All->R.string.no_files_found;RecentDocumentFilter.Pdf->R.string.no_pdf_files;RecentDocumentFilter.Word->R.string.no_word_files;RecentDocumentFilter.Excel->R.string.no_excel_files;RecentDocumentFilter.PowerPoint->R.string.no_ppt_files;RecentDocumentFilter.Text->R.string.no_txt_files}
@Composable private fun BoxScope.RecentMessage(@StringRes title:Int,@StringRes supporting:Int?){Column(Modifier.align(Alignment.Center).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.FolderOff,null,Modifier.size(52.dp));Text(stringResource(title),style=MaterialTheme.typography.titleMedium);supporting?.let{Text(stringResource(it),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable internal fun highlightFilename(name:String,query:String):AnnotatedString{val color=MaterialTheme.colorScheme.secondaryContainer;return buildAnnotatedString{append(name);FilenameSearch.matchRanges(name,query).forEach{range->addStyle(SpanStyle(background=color,fontWeight=FontWeight.SemiBold),range.first,range.last+1)}}}
