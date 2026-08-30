package com.droidnova.allfilereader.ui.screens.excel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.data.excel.*
import com.droidnova.allfilereader.BuildConfig
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

enum class ExcelReaderPhase { Resolving, Validating, PreparingViewer, ParsingWorkbook, Ready, EmptyWorkbook, MissingFile, PermissionDenied, UnsupportedFormat, UnsupportedLegacyVersion, WrongOleFormat, FormatMismatch, PasswordProtected, FileTooLarge, SafetyLimit, Corrupted, WebViewUnavailable, RendererProcessCrashed, ParseTimeout, ParseFailure }
data class ExcelReady(val file:File,val token:String,val mediaType:String,val expectedFormat:SpreadsheetExpectedFormat)
data class ExcelReaderUiState(val fileName:String?=null,val phase:ExcelReaderPhase=ExcelReaderPhase.Resolving,val ready:ExcelReady?=null)
@HiltViewModel class ExcelReaderViewModel @Inject constructor(saved:SavedStateHandle,private val repository:DocumentRepository,private val permissions:MediaPermissionManager,@ApplicationContext context:Context):ViewModel(){
 private val id=saved.get<String>("documentId").orEmpty();private val preflight=SpreadsheetPreflight(context.contentResolver,context.cacheDir);private val _state=MutableStateFlow(ExcelReaderUiState());val state:StateFlow<ExcelReaderUiState> =_state.asStateFlow();private var job:Job?=null;private var temp:File?=null
 init{load()} fun retry(){if(job?.isActive!=true)load()} fun onResume(){if(_state.value.phase==ExcelReaderPhase.PermissionDenied)load()} fun viewerPhase(phase:ExcelReaderPhase){_state.update{it.copy(phase=phase)}}
 private fun load(){job?.cancel();cleanup();_state.value=ExcelReaderUiState();job=viewModelScope.launch(Dispatchers.IO){try{if(!permissions.isGranted())throw SecurityException();val d=repository.resolveDocument(id)?:throw FileNotFoundException();if(BuildConfig.DEBUG)Log.i(TAG,"stage=RESOLUTION result=SUCCESS");show(d.displayName,ExcelReaderPhase.Validating);val session=withTimeout(30_000){preflight.copyAndValidate(Uri.parse(d.uri),d.mimeType,d.extension,d.sizeBytes)};temp=session.file;val token=ByteArray(24).also(SecureRandom()::nextBytes).joinToString(""){"%02x".format(it)};withContext(Dispatchers.Main.immediate){_state.value=ExcelReaderUiState(d.displayName,ExcelReaderPhase.PreparingViewer,ExcelReady(session.file,token,session.mediaType,session.expectedFormat))}}catch(t:TimeoutCancellationException){cleanup();if(BuildConfig.DEBUG)Log.w(TAG,"stage=PREFLIGHT result=TIMEOUT exception=${t.javaClass.simpleName}");withContext(NonCancellable+Dispatchers.Main.immediate){_state.update{it.copy(phase=ExcelReaderPhase.ParseTimeout)}}}catch(c:CancellationException){cleanup();throw c}catch(e:Exception){cleanup();val phase=spreadsheetFailurePhase(e);if(BuildConfig.DEBUG)Log.w(TAG,"stage=LOAD result=${phase.name} exception=${e.javaClass.simpleName}");withContext(Dispatchers.Main.immediate){_state.update{it.copy(phase=phase)}}}}}
 private suspend fun show(name:String,phase:ExcelReaderPhase)=withContext(Dispatchers.Main.immediate){_state.value=ExcelReaderUiState(name,phase)}
 private fun cleanup(){temp?.delete();temp=null}override fun onCleared(){job?.cancel();cleanup();super.onCleared()}companion object{const val TAG="SpreadsheetTrace"}
}

internal fun spreadsheetFailurePhase(error: Exception): ExcelReaderPhase = when(error){
 is SecurityException->ExcelReaderPhase.PermissionDenied
 is FileNotFoundException->ExcelReaderPhase.MissingFile
 is SpreadsheetPreflightException.TooLarge->ExcelReaderPhase.FileTooLarge
 is SpreadsheetPreflightException.Encrypted->ExcelReaderPhase.PasswordProtected
 is SpreadsheetPreflightException.WrongOleDocument->ExcelReaderPhase.WrongOleFormat
 is SpreadsheetPreflightException.UnsupportedBiff->ExcelReaderPhase.UnsupportedLegacyVersion
 is SpreadsheetPreflightException.FormatMismatch->ExcelReaderPhase.FormatMismatch
 is SpreadsheetPreflightException.Unsupported->ExcelReaderPhase.UnsupportedFormat
 is SpreadsheetPreflightException.Corrupted->ExcelReaderPhase.Corrupted
 is SpreadsheetPreflightException.Safety->ExcelReaderPhase.SafetyLimit
 else->ExcelReaderPhase.ParseFailure
}
