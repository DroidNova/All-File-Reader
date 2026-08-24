package com.droidnova.allfilereader.ui.screens.excel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.excel.XlsxPreflight
import com.droidnova.allfilereader.data.excel.XlsxPreflightException
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ExcelReaderPhase { Resolving, Validating, PreparingViewer, ParsingWorkbook, Ready, MissingFile, PermissionDenied, UnsupportedLegacyXls, UnsupportedEncryptedWorkbook, FileTooLarge, UnsafeOrCorruptedWorkbook, WorkbookLimitsExceeded, WebViewUnavailable, RendererProcessCrashed, ParseTimeout, ParseFailure }
data class ExcelReady(val file: File, val token: String)
data class ExcelReaderUiState(val fileName: String?=null,val phase:ExcelReaderPhase=ExcelReaderPhase.Resolving,val ready:ExcelReady?=null)

@HiltViewModel class ExcelReaderViewModel @Inject constructor(saved:SavedStateHandle,private val repository:DocumentRepository,private val permissions:MediaPermissionManager,@ApplicationContext context:Context):ViewModel(){
 private val id=saved.get<String>("documentId").orEmpty(); private val preflight=XlsxPreflight(context.contentResolver,context.cacheDir)
 private val _state=MutableStateFlow(ExcelReaderUiState());val state:StateFlow<ExcelReaderUiState> = _state.asStateFlow();private var job:Job?=null;private var temp:File?=null
 init{load()} fun retry(){if(job?.isActive!=true)load()} fun onResume(){if(_state.value.phase==ExcelReaderPhase.PermissionDenied)load()}
 fun viewerPhase(phase:ExcelReaderPhase){_state.update{it.copy(phase=phase)}}
 private fun load(){job?.cancel();cleanup();_state.value=ExcelReaderUiState();job=viewModelScope.launch(Dispatchers.IO){try{if(!permissions.isGranted())throw SecurityException();val d=repository.resolveDocument(id)?:throw FileNotFoundException();if(d.extension.equals("xls",true)||d.mimeType.equals("application/vnd.ms-excel",true)){show(d.displayName,ExcelReaderPhase.UnsupportedLegacyXls);return@launch};if(!d.extension.equals("xlsx",true)||!d.mimeType.equals(MIME,true)){show(d.displayName,ExcelReaderPhase.UnsafeOrCorruptedWorkbook);return@launch};show(d.displayName,ExcelReaderPhase.Validating);val f=preflight.copyAndValidate(Uri.parse(d.uri));temp=f;val token=ByteArray(24).also(SecureRandom()::nextBytes).joinToString(""){"%02x".format(it)};withContext(Dispatchers.Main.immediate){_state.value=ExcelReaderUiState(d.displayName,ExcelReaderPhase.PreparingViewer,ExcelReady(f,token))}}catch(c:CancellationException){cleanup();throw c}catch(e:Exception){cleanup();val p=when(e){is SecurityException->ExcelReaderPhase.PermissionDenied;is FileNotFoundException->ExcelReaderPhase.MissingFile;is XlsxPreflightException.Encrypted->ExcelReaderPhase.UnsupportedEncryptedWorkbook;is XlsxPreflightException.Unsafe->ExcelReaderPhase.FileTooLarge;else->ExcelReaderPhase.UnsafeOrCorruptedWorkbook};withContext(Dispatchers.Main.immediate){_state.update{it.copy(phase=p)}}}}}
 private suspend fun show(n:String,p:ExcelReaderPhase)=withContext(Dispatchers.Main.immediate){_state.value=ExcelReaderUiState(n,p)}
 private fun cleanup(){temp?.delete();temp=null} override fun onCleared(){job?.cancel();cleanup();super.onCleared()}
 companion object{const val MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}
}
