package com.droidnova.allfilereader.navigation

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidnova.allfilereader.domain.model.DocumentFile
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
    private var pending: Intent? = restoredIntent()
    private val _state = MutableStateFlow<IncomingUiState>(if (pending == null) IncomingUiState.Idle else IncomingUiState.PendingPermission)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        generation++
        job?.cancel()
        pending = Intent(Intent.ACTION_VIEW, intent.data).apply {
            type = intent.type
            flags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        savePending(pending)
        _state.value = IncomingUiState.PendingPermission
    }

    fun processPending(permissionGranted: Boolean) {
        val request = pending ?: return
        if (!permissionGranted) { _state.value = IncomingUiState.PendingPermission; return }
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
                        IncomingUiState.Open(result.document)
                    }
                    is IncomingResolution.Error -> IncomingUiState.Failure(result.reason)
                }
            }
        }
    }

    fun consumed() { if (_state.value is IncomingUiState.Open) _state.value = IncomingUiState.Idle }
    fun dismissError() { if (_state.value is IncomingUiState.Failure) _state.value = IncomingUiState.Idle }

    private fun savePending(intent: Intent?) {
        saved[URI] = intent?.dataString
        saved[MIME] = intent?.type
        saved[FLAGS] = intent?.flags ?: 0
    }
    private fun restoredIntent(): Intent? = saved.get<String>(URI)?.let { value ->
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(value)).apply {
            type = saved.get<String>(MIME)
            flags = saved.get<Int>(FLAGS) ?: 0
        }
    }
    private fun clearSaved() { saved.remove<String>(URI); saved.remove<String>(MIME); saved.remove<Int>(FLAGS) }
    override fun onCleared() { job?.cancel(); super.onCleared() }
    private companion object { const val URI = "incoming_uri"; const val MIME = "incoming_mime"; const val FLAGS = "incoming_flags" }
}
