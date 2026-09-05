package com.droidnova.allfilereader.ui.screens.powerpoint

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.data.powerpoint.*
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PptxPhase {
    Resolving, Copying, Validating, Preparing, Rendering, Ready,
    Missing, PermissionDenied, LegacyValid, LegacyInvalid, LegacyAccessDenied,
    LegacyPreparationFailed, LegacyNoCompatibleApp, MacroUnsupported, Encrypted,
    InvalidContainer, MissingParts, TooLargeForDevice, UnsafeArchive,
    RendererAssetFailure, RendererFailure, RendererStalled, InsufficientMemory, Failure
}

data class PptxReady(val file: File, val token: String, val attemptId: Long, val stallMillis: Long)
data class PptxState(
    val fileName: String? = null,
    val phase: PptxPhase = PptxPhase.Resolving,
    val ready: PptxReady? = null,
    val legacyLocation: String? = null
)

@HiltViewModel
class PowerPointReaderViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repository: DocumentRepository,
    private val permissions: MediaPermissionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val id = saved.get<String>("documentId").orEmpty()
    private val sessionStore = PptxSessionStore(context.cacheDir)
    private val budget = PptxRenderBudgetPolicy.forProfile(PptxRenderBudgetPolicy.profile(context))
    private val preflight = PptxPreflight(context.contentResolver, sessionStore, budget)
    private val legacyResolver = LegacyPptSourceResolver(context)
    private val attempts = AtomicLong()
    private val _state = MutableStateFlow(PptxState())
    val state = _state.asStateFlow()
    private var openJob: Job? = null

    init { load() }
    fun retry() = load()
    fun onResume() { if (state.value.phase == PptxPhase.PermissionDenied) load() }

    fun viewerPhase(token: String, phase: PptxPhase) {
        val ready = _state.value.ready ?: return
        if (ready.token != token || phase !in RENDERER_PHASES) return
        debug("attempt=${ready.attemptId} renderer=${phase.name}")
        _state.update { current -> if (current.ready?.token == token) current.copy(phase = phase) else current }
    }

    fun releaseReader(token: String, reason: String = "SCREEN_DISPOSED") {
        val ready = _state.value.ready ?: return
        if (ready.token != token) return
        debug("attempt=${ready.attemptId} cleanup=$reason")
        sessionStore.release(token, ready.file)
        _state.update { current -> if (current.ready?.token == token) current.copy(ready = null) else current }
    }

    suspend fun prepareLegacyShare(): Uri? = withContext(Dispatchers.IO) {
        val location = state.value.legacyLocation ?: return@withContext null
        when (val resolved = legacyResolver.resolve(location, "ppt")) {
            is LegacyPptResolution.Error -> { showLegacyError(resolved.code); null }
            is LegacyPptResolution.Valid -> when (val shared = legacyResolver.prepareForExternalOpen(resolved.source)) {
                is LegacyPptShareResult.Ready -> shared.uri
                is LegacyPptShareResult.Error -> { showLegacyError(shared.code); null }
            }
        }
    }

    fun legacyOpenResult(result: LegacyPptOpenResult) {
        _state.update { it.copy(phase = when (result) {
            LegacyPptOpenResult.Launched -> PptxPhase.LegacyValid
            LegacyPptOpenResult.NoCompatibleApp -> PptxPhase.LegacyNoCompatibleApp
            LegacyPptOpenResult.AccessDenied -> PptxPhase.LegacyAccessDenied
        }) }
    }

    private fun load() {
        openJob?.cancel()
        _state.value.ready?.let { sessionStore.release(it.token, it.file) }
        val attemptId = attempts.incrementAndGet()
        _state.value = PptxState(phase = PptxPhase.Resolving)
        openJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var token: String? = null
            try {
                publish(attemptId, PptxState(phase = PptxPhase.Resolving))
                if (!permissions.isGranted()) throw SecurityException()
                val document = repository.resolveDocument(id) ?: throw FileNotFoundException()
                val extension = document.extension?.lowercase()
                if (extension == "ppt") {
                    val phase = when (val result = legacyResolver.resolve(document.uri, extension)) {
                        is LegacyPptResolution.Valid -> PptxPhase.LegacyValid
                        is LegacyPptResolution.Error -> legacyPhase(result.code)
                    }
                    return@launch publish(attemptId, PptxState(document.displayName, phase, legacyLocation = document.uri))
                }
                if (extension in setOf("pptm", "pps", "ppsx") || (extension != "pptx" && document.mimeType?.lowercase() != PPTX_MIME)) {
                    return@launch publish(attemptId, PptxState(document.displayName, PptxPhase.MacroUnsupported))
                }
                token = sessionStore.newId()
                publish(attemptId, PptxState(document.displayName, PptxPhase.Copying))
                val session = preflight.copyAndValidate(Uri.parse(document.uri), token) {
                    publish(attemptId, PptxState(document.displayName, PptxPhase.Validating))
                }
                coroutineContext.ensureActive()
                debug("attempt=$attemptId stage=VALIDATED declaredSizeKnown=${session.declaredBytes != null} bytes=${session.byteCount} entries=${session.entryCount} uncompressed=${session.uncompressedBytes} budget=${budget.category}")
                publish(attemptId, PptxState(document.displayName, PptxPhase.Preparing, PptxReady(session.file, token, attemptId, budget.rendererStallMillis)))
            } catch (cancelled: CancellationException) {
                token?.let { sessionStore.release(it, null) }
                debug("attempt=$attemptId cleanup=CANCELLED")
                throw cancelled
            } catch (error: Exception) {
                token?.let { sessionStore.release(it, File(context.cacheDir, "${PptxSessionStore.DIRECTORY_NAME}/$it.pptx")) }
                val phase = mapFailure(error)
                debug("attempt=$attemptId result=${phase.name} exception=${error.javaClass.simpleName} elapsedMs=${System.currentTimeMillis()-started}")
                publish(attemptId, _state.value.copy(phase = phase, ready = null))
            }
        }
    }

    private suspend fun publish(attemptId: Long, value: PptxState) = withContext(Dispatchers.Main.immediate) {
        if (attemptId == attempts.get()) _state.value = value
    }
    private fun mapFailure(error: Exception) = when (error) {
        is SecurityException -> PptxPhase.PermissionDenied
        is FileNotFoundException, is PptxPreflightException.CopyFailed -> PptxPhase.Missing
        is PptxPreflightException.Empty, is PptxPreflightException.Corrupt -> PptxPhase.InvalidContainer
        is PptxPreflightException.MissingParts -> PptxPhase.MissingParts
        is PptxPreflightException.Encrypted -> PptxPhase.Encrypted
        is PptxPreflightException.TooLarge -> PptxPhase.TooLargeForDevice
        is PptxPreflightException.Unsafe -> PptxPhase.UnsafeArchive
        else -> PptxPhase.Failure
    }
    private fun legacyPhase(code: LegacyPptErrorCode) = when (code) {
        LegacyPptErrorCode.INPUT_EMPTY, LegacyPptErrorCode.INVALID_OLE_SIGNATURE -> PptxPhase.LegacyInvalid
        LegacyPptErrorCode.TEMP_COPY_FAILED, LegacyPptErrorCode.INSUFFICIENT_STORAGE, LegacyPptErrorCode.FILE_PROVIDER_FAILED -> PptxPhase.LegacyPreparationFailed
        else -> PptxPhase.LegacyAccessDenied
    }
    private suspend fun showLegacyError(code: LegacyPptErrorCode) = withContext(Dispatchers.Main.immediate) {
        _state.update { it.copy(phase = legacyPhase(code)) }
    }
    override fun onCleared() {
        openJob?.cancel()
        _state.value.ready?.let { sessionStore.release(it.token, it.file) }
        super.onCleared()
    }
    private fun debug(message: String) { if (BuildConfig.DEBUG) Log.d(TAG, message) }
    companion object {
        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val TAG = "PptxReader"
        private val RENDERER_PHASES = setOf(PptxPhase.Rendering, PptxPhase.Ready, PptxPhase.RendererAssetFailure, PptxPhase.RendererFailure, PptxPhase.RendererStalled, PptxPhase.InsufficientMemory)
    }
}
