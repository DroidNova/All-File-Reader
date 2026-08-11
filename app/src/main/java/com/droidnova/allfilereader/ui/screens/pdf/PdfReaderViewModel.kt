package com.droidnova.allfilereader.ui.screens.pdf

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.pdf.EmptyPdfException
import com.droidnova.allfilereader.data.pdf.NativePdfDocument
import com.droidnova.allfilereader.data.pdf.PdfFileNotFoundException
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PdfDocumentState {
    data object Loading : PdfDocumentState
    data class Ready(val fileName: String, val pageCount: Int) : PdfDocumentState
    data object NotFound : PdfDocumentState
    data object AccessDenied : PdfDocumentState
    data object Empty : PdfDocumentState
    data object Unsupported : PdfDocumentState
}
sealed interface PdfPageState { data object Loading:PdfPageState;data class Ready(val bitmap:Bitmap):PdfPageState;data object Error:PdfPageState }
data class PdfReaderUiState(val document:PdfDocumentState=PdfDocumentState.Loading,val pages:Map<Int,PdfPageState> = emptyMap(),val currentPage:Int=0)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DocumentRepository,
    private val pdfDocument: NativePdfDocument,
    private val permissionManager: MediaPermissionManager
) : ViewModel() {
    private val documentId: String = savedStateHandle["documentId"].orEmpty()
    private val _uiState=MutableStateFlow(PdfReaderUiState())
    val uiState:StateFlow<PdfReaderUiState> = _uiState.asStateFlow()
    private val renderJobs=mutableMapOf<Int,Job>()
    private val cacheOrder=ArrayDeque<Int>()
    private var openJob:Job?=null
    init { open() }

    fun retry(){open()}
    fun onResume(){if(_uiState.value.document is PdfDocumentState.AccessDenied)open()}
    fun setCurrentPage(page:Int){_uiState.value=_uiState.value.copy(currentPage=page)}
    fun renderPage(page:Int,width:Int){
        if(width<=0||_uiState.value.pages[page] is PdfPageState.Ready||renderJobs[page]?.isActive==true)return
        _uiState.value=_uiState.value.copy(pages=_uiState.value.pages+(page to PdfPageState.Loading))
        renderJobs[page]=viewModelScope.launch {
            try { val bitmap=pdfDocument.renderPage(page,width);putPage(page,PdfPageState.Ready(bitmap)) }
            catch(c:CancellationException){throw c}catch(_:Exception){putPage(page,PdfPageState.Error)}
        }
    }

    private fun open(){if(openJob?.isActive==true)return;openJob=viewModelScope.launch{
        clearPages();_uiState.value=PdfReaderUiState()
        if(!permissionManager.isGranted()){
            _uiState.value=PdfReaderUiState(PdfDocumentState.AccessDenied)
            return@launch
        }
        val document=repository.resolveDocument(documentId)
        if(document==null){_uiState.value=PdfReaderUiState(PdfDocumentState.NotFound);return@launch}
        if(document.category!=DocumentCategory.Pdf){_uiState.value=PdfReaderUiState(PdfDocumentState.Unsupported);return@launch}
        try { val count=pdfDocument.open(document);_uiState.value=PdfReaderUiState(PdfDocumentState.Ready(document.displayName,count)) }
        catch(c:CancellationException){throw c}catch(_:java.io.FileNotFoundException){_uiState.value=PdfReaderUiState(PdfDocumentState.NotFound)}
        catch(_:PdfFileNotFoundException){_uiState.value=PdfReaderUiState(PdfDocumentState.NotFound)}
        catch(_:SecurityException){_uiState.value=PdfReaderUiState(if(permissionManager.isGranted()) PdfDocumentState.Unsupported else PdfDocumentState.AccessDenied)}
        catch(_:EmptyPdfException){_uiState.value=PdfReaderUiState(PdfDocumentState.Empty)}
        catch(_:Exception){_uiState.value=PdfReaderUiState(PdfDocumentState.Unsupported)}
    }}
    private fun putPage(page:Int,state:PdfPageState){
        if(state is PdfPageState.Ready){cacheOrder.remove(page);cacheOrder.addLast(page)}
        var pages=_uiState.value.pages+(page to state)
        while(cacheOrder.size>MAX_CACHED_PAGES){val evicted=cacheOrder.removeFirst();pages-=evicted}
        _uiState.value=_uiState.value.copy(pages=pages)
    }
    private fun clearPages(){renderJobs.values.forEach{it.cancel()};renderJobs.clear();cacheOrder.clear();pdfDocument.close()}
    override fun onCleared(){clearPages();super.onCleared()}
    private companion object{const val MAX_CACHED_PAGES=4}
}
