package com.droidnova.allfilereader.navigation

import androidx.lifecycle.ViewModel
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StorageAccessState { Checking, NotGranted, Requesting, Granted }

internal class StorageAccessRequestGuard {
    var state: StorageAccessState = StorageAccessState.Checking
        private set

    fun update(granted: Boolean) {
        state = if (granted) StorageAccessState.Granted else StorageAccessState.NotGranted
    }

    fun begin(): Boolean {
        if (state != StorageAccessState.NotGranted) return false
        state = StorageAccessState.Requesting
        return true
    }

    fun dispatched() {
        if (state == StorageAccessState.Requesting) state = StorageAccessState.NotGranted
    }
}

/** The activity-scoped source of truth for whether document features may be composed. */
@HiltViewModel
class StorageAccessViewModel @Inject constructor(
    private val permissions: MediaPermissionManager,
    private val repository: DocumentRepository
) : ViewModel() {
    private val requestGuard = StorageAccessRequestGuard()
    private val _state = MutableStateFlow(requestGuard.state)
    val state: StateFlow<StorageAccessState> = _state.asStateFlow()

    init { recheck() }

    fun recheck() {
        val granted = permissions.isGranted()
        if (!granted) repository.clearSnapshots()
        requestGuard.update(granted)
        _state.value = requestGuard.state
    }

    /** Atomically claims a single user-initiated system UI launch. */
    fun beginRequest(): Boolean {
        val began = requestGuard.begin()
        _state.value = requestGuard.state
        return began
    }

    fun launchDispatched() {
        requestGuard.dispatched()
        _state.value = requestGuard.state
    }
}
