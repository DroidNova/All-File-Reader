package com.droidnova.allfilereader.ui.screens.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.data.pdf.*
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PdfDocumentState {
    data object Loading:PdfDocumentState
    data class Ready(val fileName:String,val source:PreparedPdfSource,val attemptId:Long):PdfDocumentState
    data object NotFound:PdfDocumentState;data object AccessDenied:PdfDocumentState
    data object Invalid:PdfDocumentState;data object InsufficientStorage:PdfDocumentState
    data object ViewerFailure:PdfDocumentState;data object Unsupported:PdfDocumentState
}
data class PdfReaderUiState(val document:PdfDocumentState=PdfDocumentState.Loading)

@HiltViewModel class PdfReaderViewModel @Inject constructor(saved:SavedStateHandle,private val repository:DocumentRepository,private val permissionManager:MediaPermissionManager,@ApplicationContext context:Context):ViewModel(){
    private val documentId=saved.get<String>("documentId").orEmpty();private val sessionDirectory=File(context.cacheDir,"pdf_sessions").apply{mkdirs()};private val preparer=PdfSourcePreparer(context.contentResolver,sessionDirectory)
    private val _uiState=MutableStateFlow(PdfReaderUiState());val uiState:StateFlow<PdfReaderUiState> =_uiState.asStateFlow();private var job:Job?=null;private var attempt=0L;private val owned=mutableMapOf<Long,File>()
    init{PdfSessionFiles.cleanStale(sessionDirectory,System.currentTimeMillis());open()}
    fun retry()=open();fun onResume(){if(_uiState.value.document is PdfDocumentState.AccessDenied)open()}
    fun viewerFailed(id:Long){val ready=_uiState.value.document as? PdfDocumentState.Ready?:return;if(ready.attemptId!=id)return;_uiState.value=PdfReaderUiState(PdfDocumentState.ViewerFailure)}
    fun releaseSource(id:Long){val file=owned.remove(id)?:return;viewModelScope.launch(Dispatchers.IO){delay(1_000);PdfSessionFiles.deleteOwned(file,sessionDirectory)}}
    private fun open(){job?.cancel();val id=++attempt;_uiState.value=PdfReaderUiState();job=viewModelScope.launch(Dispatchers.IO){var prepared:PreparedPdfSource?=null;try{if(!permissionManager.isGranted())throw SecurityException();val document=repository.resolveDocument(documentId)?:throw FileNotFoundException();if(document.category!=DocumentCategory.Pdf){publish(id,PdfDocumentState.Unsupported);return@launch};val uri=Uri.parse(document.uri);if(uri.scheme!="content"&&uri.scheme!="file")throw PdfPreparationException.Invalid();prepared=preparer.prepare(uri,document.sizeBytes);ensureActive();if(id!=attempt){PdfSessionFiles.deleteOwned(prepared.ownedFile,sessionDirectory);return@launch};prepared.ownedFile?.let{owned[id]=it};if(BuildConfig.DEBUG)Log.i(TAG,"attempt=$id strategy=${prepared.strategy} declaredSizeKnown=${document.sizeBytes>=0} copiedBytes=${prepared.actualBytes?:-1} preflight=VALID");publish(id,PdfDocumentState.Ready(document.displayName,prepared,id))}catch(c:CancellationException){PdfSessionFiles.deleteOwned(prepared?.ownedFile,sessionDirectory);throw c}catch(e:Exception){PdfSessionFiles.deleteOwned(prepared?.ownedFile,sessionDirectory);val state=when(e){is SecurityException->PdfDocumentState.AccessDenied;is FileNotFoundException->PdfDocumentState.NotFound;is PdfPreparationException.Empty,is PdfPreparationException.Invalid,is IllegalArgumentException->PdfDocumentState.Invalid;is PdfPreparationException.InsufficientStorage->PdfDocumentState.InsufficientStorage;else->PdfDocumentState.NotFound};if(BuildConfig.DEBUG)Log.w(TAG,"attempt=$id stage=failed code=${state.javaClass.simpleName} exception=${e.javaClass.simpleName}");publish(id,state)}}}
    private suspend fun publish(id:Long,state:PdfDocumentState)=withContext(Dispatchers.Main.immediate){if(id==attempt)_uiState.value=PdfReaderUiState(state)}
    override fun onCleared(){attempt++;job?.cancel();owned.values.forEach{PdfSessionFiles.deleteOwned(it,sessionDirectory)};owned.clear();super.onCleared()}
    private companion object{const val TAG="PdfReaderTrace"}
}
