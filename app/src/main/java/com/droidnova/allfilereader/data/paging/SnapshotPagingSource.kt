package com.droidnova.allfilereader.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads one immutable metadata snapshot and serves offset pages without rescanning on append. */
class SnapshotPagingSource<T : Any>(private val snapshotLoader: suspend () -> List<T>) : PagingSource<Int, T>() {
    private var snapshot: List<T>? = null
    private val snapshotMutex = Mutex()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val data = snapshot ?: snapshotMutex.withLock {
            snapshot ?: snapshotLoader().toList().also { snapshot = it }
        }
        val offset = (params.key ?: 0).coerceIn(0, data.size)
        val end = (offset + params.loadSize).coerceAtMost(data.size)
        LoadResult.Page(
            data = data.subList(offset, end),
            prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
            nextKey = if (end >= data.size) null else end
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(state.config.pageSize)
            ?: page.nextKey?.minus(state.config.pageSize)?.coerceAtLeast(0)
    }
}

val LocalMetadataPagingConfig = androidx.paging.PagingConfig(
    pageSize = 40,
    initialLoadSize = 80,
    prefetchDistance = 10,
    enablePlaceholders = false,
    maxSize = 240
)
