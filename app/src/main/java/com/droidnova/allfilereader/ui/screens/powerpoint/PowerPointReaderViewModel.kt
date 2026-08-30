package com.droidnova.allfilereader.ui.screens.powerpoint

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.powerpoint.*
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.*
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class PptxPhase { Resolving,Validating,Preparing,Rendering,Ready,Missing,PermissionDenied,LegacyValid,LegacyInvalid,LegacyAccessDenied,LegacyPreparationFailed,LegacyNoCompatibleApp,MacroUnsupported,Encrypted,Corrupt,SafetyLimit,RenderTimeout,RendererCrash,Failure }
data class PptxReady(val file:File,val token:String)
data class PptxState(val fileName:String?=null,val phase:PptxPhase=PptxPhase.Resolving,val ready:PptxReady?=null,val legacyLocation:String?=null)
@HiltViewModel class PowerPointReaderViewModel @Inject constructor(saved:SavedStateHandle,private val repository:DocumentRepository,private val permissions:MediaPermissionManager,@ApplicationContext context:Context):ViewModel(){
 private val id=saved.get<String>("documentId").orEmpty();private val preflight=PptxPreflight(context.contentResolver,context.cacheDir);private val legacyResolver=LegacyPptSourceResolver(context);private val _state=MutableStateFlow(PptxState());val state=_state.asStateFlow();private var job:Job?=null;private var temp:File?=null
 init{load()} fun retry()=load();fun onResume(){if(state.value.phase==PptxPhase.PermissionDenied)load()};fun viewerPhase(p:PptxPhase){_state.update{it.copy(phase=p)}} suspend fun prepareLegacyShare():Uri?=withContext(Dispatchers.IO){val location=state.value.legacyLocation?:return@withContext null;when(val resolved=legacyResolver.resolve(location,"ppt")){is LegacyPptResolution.Error->{showLegacyError(resolved.code);null};is LegacyPptResolution.Valid->when(val shared=legacyResolver.prepareForExternalOpen(resolved.source)){is LegacyPptShareResult.Ready->shared.uri;is LegacyPptShareResult.Error->{showLegacyError(shared.code);null}}}} fun legacyOpenResult(r:LegacyPptOpenResult){_state.update{it.copy(phase=when(r){LegacyPptOpenResult.Launched->PptxPhase.LegacyValid;LegacyPptOpenResult.NoCompatibleApp->PptxPhase.LegacyNoCompatibleApp;LegacyPptOpenResult.AccessDenied->PptxPhase.LegacyAccessDenied})}}
 private fun load(){job?.cancel();cleanup();job=viewModelScope.launch(Dispatchers.IO){try{_state.value=PptxState();if(!permissions.isGranted())throw SecurityException();val d=repository.resolveDocument(id)?:throw FileNotFoundException();val ext=d.extension?.lowercase();if(ext=="ppt"){val phase=when(val result=legacyResolver.resolve(d.uri,ext)){is LegacyPptResolution.Valid->PptxPhase.LegacyValid;is LegacyPptResolution.Error->legacyPhase(result.code)};return@launch withContext(Dispatchers.Main.immediate){_state.value=PptxState(d.displayName,phase,legacyLocation=d.uri)}};if(ext in setOf("pptm","pps","ppsx"))return@launch show(d.displayName,PptxPhase.MacroUnsupported);if(ext!="pptx"&&d.mimeType?.lowercase()!=PPTX_MIME) return@launch show(d.displayName,PptxPhase.MacroUnsupported);show(d.displayName,PptxPhase.Validating);val s=withTimeout(30_000){preflight.copyAndValidate(Uri.parse(d.uri))};temp=s.file;val token=ByteArray(24).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)};Log.i(TAG,"validation=SUCCESS");withContext(Dispatchers.Main){_state.value=PptxState(d.displayName,PptxPhase.Preparing,PptxReady(s.file,token))}}catch(e:TimeoutCancellationException){cleanup();withContext(Dispatchers.Main){_state.update{it.copy(phase=PptxPhase.SafetyLimit)}}}catch(e:CancellationException){cleanup();throw e}catch(e:Throwable){cleanup();val p=when(e){is SecurityException->PptxPhase.PermissionDenied;is FileNotFoundException->PptxPhase.Missing;is PptxPreflightException.Encrypted->PptxPhase.Encrypted;is PptxPreflightException.Unsafe->PptxPhase.SafetyLimit;is PptxPreflightException.Corrupt->PptxPhase.Corrupt;else->PptxPhase.Failure};Log.w(TAG,"reader stage failed: ${e.javaClass.simpleName}");withContext(Dispatchers.Main){_state.update{it.copy(phase=p)}}}}}
 private fun legacyPhase(code:LegacyPptErrorCode)=when(code){
  LegacyPptErrorCode.INPUT_EMPTY,LegacyPptErrorCode.INVALID_OLE_SIGNATURE->PptxPhase.LegacyInvalid
  LegacyPptErrorCode.TEMP_COPY_FAILED,LegacyPptErrorCode.INSUFFICIENT_STORAGE,LegacyPptErrorCode.FILE_PROVIDER_FAILED->PptxPhase.LegacyPreparationFailed
  else->PptxPhase.LegacyAccessDenied
 }
 private suspend fun showLegacyError(code:LegacyPptErrorCode){
  withContext(Dispatchers.Main.immediate){_state.update{it.copy(phase=legacyPhase(code))}}
 }
 private suspend fun show(n:String,p:PptxPhase)=withContext(Dispatchers.Main){_state.value=PptxState(n,p)};private fun cleanup(){temp?.delete();temp=null};override fun onCleared(){job?.cancel();cleanup();super.onCleared()}
 companion object{const val PPTX_MIME="application/vnd.openxmlformats-officedocument.presentationml.presentation";const val TAG="PptxReader"}
}
