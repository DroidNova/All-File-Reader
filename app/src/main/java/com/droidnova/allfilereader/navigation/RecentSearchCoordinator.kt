package com.droidnova.allfilereader.navigation

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SearchActivationSource { Home, Recent }
data class SearchActivationRequest(
    val id: Long,
    val source: SearchActivationSource,
    val resetCategoryToAll: Boolean,
    val clearQuery: Boolean
)

/** Process-scoped, non-lossy hand-off between Home navigation and the Recent destination. */
@Singleton class RecentSearchCoordinator @Inject constructor() {
    private val ids = AtomicLong()
    private val _pending = MutableStateFlow<SearchActivationRequest?>(null)
    val pending = _pending.asStateFlow()
    fun request(
        source: SearchActivationSource,
        resetCategoryToAll: Boolean = false,
        clearQuery: Boolean = false
    ): Long = ids.incrementAndGet().also { id ->
        _pending.value = SearchActivationRequest(id, source, resetCategoryToAll, clearQuery)
    }
    fun acknowledge(id: Long) = _pending.update { if (it?.id == id) null else it }
}
