package com.droidnova.allfilereader.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IncomingUiState {
    data object Idle : IncomingUiState
    data object PendingPermission : IncomingUiState
    data class Open(val document: DocumentFile) : IncomingUiState
    data class Failure(val reason: IncomingError) : IncomingUiState
}

/** Activity-scoped, generation-guarded intake state. It never retains an Activity context. */
@HiltViewModel
class IncomingDocumentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repository: DocumentRepository
) : ViewModel() {
    private val resolver = IncomingDocumentResolver(context.contentResolver)
    private val saved = savedStateHandle
    private var pending: IncomingRequest? = restoredRequest()
    private val _state = MutableStateFlow<IncomingUiState>(if (pending == null) IncomingUiState.Idle else IncomingUiState.PendingPermission)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        traceIntent(intent)
        generation++
        job?.cancel()
        when (val extraction = IncomingUriExtractor.extract(intent)) {
            IncomingExtraction.Missing -> {
                pending = null; clearSaved(); _state.value = IncomingUiState.Failure(IncomingError.MissingUri)
                trace("extraction_source=none code=MISSING_URI")
                return
            }
            IncomingExtraction.Ambiguous -> {
                pending = null; clearSaved(); _state.value = IncomingUiState.Failure(IncomingError.AmbiguousUri)
                trace("extraction_source=ambiguous code=AMBIGUOUS_URI")
                return
            }
            is IncomingExtraction.Ready -> pending = extraction.request
        }
        trace("extraction_source=${pending?.source?.name} code=URI_EXTRACTED")
        savePending(pending)
        _state.value = IncomingUiState.PendingPermission
    }

    fun processPending(permissionGranted: Boolean) {
        val request = pending ?: return
        if (!permissionGranted) {
            trace("permission_gate=blocked navigation_stage=pending")
            _state.value = IncomingUiState.PendingPermission
            return
        }
        trace("permission_gate=granted navigation_stage=preparing")
        val ownGeneration = generation
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val result = resolver.resolve(request)
            withContext(Dispatchers.Main.immediate) {
                if (ownGeneration != generation || pending !== request) return@withContext
                pending = null
                clearSaved()
                _state.value = when (result) {
                    is IncomingResolution.Ready -> {
                        repository.rememberDocument(result.document)
                        trace("navigation_stage=ready selected_reader=${result.destination?.name ?: "unsupported"}")
                        IncomingUiState.Open(result.document)
                    }
                    is IncomingResolution.Error -> IncomingUiState.Failure(result.reason)
                }
            }
        }
    }

    fun consumed() { if (_state.value is IncomingUiState.Open) _state.value = IncomingUiState.Idle }
    fun dismissError() { if (_state.value is IncomingUiState.Failure) _state.value = IncomingUiState.Idle }

    private fun savePending(request: IncomingRequest?) {
        saved[URI] = request?.uri?.toString()
        saved[MIME] = request?.declaredMimeType
        saved[FLAGS] = request?.readGrantFlags ?: 0
        saved[SOURCE] = request?.source?.name
    }
    private fun restoredRequest(): IncomingRequest? = saved.get<String>(URI)?.let { value ->
        IncomingRequest(Uri.parse(value), saved.get<String>(MIME), saved.get<Int>(FLAGS) ?: 0,
            saved.get<String>(SOURCE)?.let { runCatching { IncomingUriSource.valueOf(it) }.getOrNull() } ?: IncomingUriSource.Data)
    }
    private fun clearSaved() { saved.remove<String>(URI); saved.remove<String>(MIME); saved.remove<Int>(FLAGS); saved.remove<String>(SOURCE) }
    override fun onCleared() { job?.cancel(); super.onCleared() }
    private fun traceIntent(intent: Intent) {
        val data = runCatching { intent.data }.getOrNull()
        val clipCount = runCatching { intent.clipData?.itemCount ?: 0 }.getOrDefault(-1)
        val streamPresent = runCatching { intent.hasExtra(Intent.EXTRA_STREAM) }.getOrDefault(false)
        trace("incoming_action=${intent.action} declared_mime=${intent.type ?: "none"} data_present=${data != null} " +
            "data_scheme=${data?.scheme ?: "none"} authority=${data?.authority?.take(80)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "none"} " +
            "clip_items=$clipCount extra_stream=$streamPresent " +
            "read_grant=${intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0}")
    }
    private fun trace(message: String) { if (BuildConfig.DEBUG) Log.d("ExternalDocumentOpen", message) }
    private companion object {
        const val URI = "incoming_uri"; const val MIME = "incoming_mime"; const val FLAGS = "incoming_flags"; const val SOURCE = "incoming_source"
    }
}
