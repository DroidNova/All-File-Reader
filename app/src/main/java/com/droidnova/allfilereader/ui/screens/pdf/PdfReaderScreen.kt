package com.droidnova.allfilereader.ui.screens.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun PdfReaderScreen(onBack:()->Unit,viewModel:PdfReaderViewModel=hiltViewModel()){
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}}
    val requestAccess=rememberStorageAccessRequest(viewModel::onResume)
    PdfReaderContent(state,onBack,requestAccess,viewModel::renderPage,viewModel::setCurrentPage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfReaderContent(state:PdfReaderUiState,onBack:()->Unit,onAllowAccess:()->Unit,onRender:(Int,Int)->Unit,onPageChanged:(Int)->Unit){
    val ready=state.document as? PdfDocumentState.Ready
    Scaffold(topBar={TopAppBar(
        title={Column{Text(ready?.fileName?:stringResource(R.string.pdf_reader),maxLines=1,overflow=TextOverflow.Ellipsis);if(ready!=null)Text(stringResource(R.string.pdf_page_position,state.currentPage+1,ready.pageCount),style=MaterialTheme.typography.labelSmall)}},
        navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,stringResource(R.string.back))}}
    )}){padding->
        when(val document=state.document){
            PdfDocumentState.Loading->Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            is PdfDocumentState.Ready->PdfPages(document.pageCount,state,padding,onRender,onPageChanged)
            PdfDocumentState.NotFound->PdfError(R.string.pdf_not_found,R.string.pdf_not_found_message,padding,onBack,null,null)
            PdfDocumentState.AccessDenied->PdfError(R.string.pdf_access_removed,R.string.pdf_access_removed_message,padding,onBack,onAllowAccess,R.string.allow_access)
            PdfDocumentState.Empty->PdfError(R.string.pdf_empty,R.string.pdf_empty_message,padding,onBack,null,null)
            PdfDocumentState.Unsupported->PdfError(R.string.pdf_unsupported,R.string.pdf_unsupported_message,padding,onBack,null,null)
        }
    }
}

@Composable
private fun PdfPages(pageCount:Int,state:PdfReaderUiState,padding:PaddingValues,onRender:(Int,Int)->Unit,onPageChanged:(Int)->Unit){
    val listState=rememberLazyListState(initialFirstVisibleItemIndex=state.currentPage.coerceIn(0,pageCount-1))
    var scale by rememberSaveable{mutableFloatStateOf(1f)}
    var offsetX by rememberSaveable{mutableFloatStateOf(0f)}
    var offsetY by rememberSaveable{mutableFloatStateOf(0f)}
    LaunchedEffect(listState){snapshotFlow{listState.firstVisibleItemIndex}.distinctUntilChanged().collect(onPageChanged)}
    LazyColumn(state=listState,modifier=Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        items(pageCount,key={it}){page->
            BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                val widthPx=with(LocalDensity.current){maxWidth.roundToPx()}.coerceAtLeast(1)
                LaunchedEffect(page,widthPx){onRender(page,widthPx)}
                Surface(tonalElevation=1.dp,modifier=Modifier.fillMaxWidth().heightIn(min=240.dp)){
                    when(val pageState=state.pages[page]){
                        is PdfPageState.Ready->Image(pageState.bitmap.asImageBitmap(),stringResource(R.string.pdf_page_content_description,page+1),Modifier.fillMaxWidth().background(Color.White).graphicsLayer{scaleX=scale;scaleY=scale;translationX=offsetX;translationY=offsetY}.pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,4f);if(scale==1f){offsetX=0f;offsetY=0f}else{offsetX=(offsetX+pan.x).coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2);offsetY=(offsetY+pan.y).coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2)}}},contentScale=ContentScale.FillWidth)
                        PdfPageState.Error->Box(Modifier.fillMaxWidth().height(240.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.BrokenImage,null);Text(stringResource(R.string.pdf_page_render_error))}}
                        else->Box(Modifier.fillMaxWidth().height(320.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                    }
                }
            }
            Text(stringResource(R.string.pdf_page_number,page+1),Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun PdfError(title:Int,message:Int,padding:PaddingValues,onBack:()->Unit,onAction:(()->Unit)?,actionLabel:Int?){Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center){Column(Modifier.padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Outlined.BrokenImage,null,Modifier.size(52.dp));Text(stringResource(title),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center);Text(stringResource(message),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant);Row{TextButton(onClick=onBack){Text(stringResource(R.string.back))};if(onAction!=null&&actionLabel!=null)Button(onClick=onAction){Text(stringResource(actionLabel))}}}}}

@Preview(showBackground = true)
@Composable
private fun PdfReaderErrorPreview() {
    MaterialTheme {
        PdfReaderContent(
            state = PdfReaderUiState(PdfDocumentState.Unsupported),
            onBack = {},
            onAllowAccess = {},
            onRender = { _, _ -> },
            onPageChanged = {}
        )
    }
}
