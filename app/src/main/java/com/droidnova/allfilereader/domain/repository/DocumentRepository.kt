package com.droidnova.allfilereader.domain.repository

import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentCategory
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DocumentRepository {
    val documents: StateFlow<List<DocumentFile>>

    suspend fun getDocuments(forceRefresh: Boolean = false): List<DocumentFile>

    fun rememberDocument(document: DocumentFile)

    suspend fun resolveDocument(id: String): DocumentFile?

    fun pagedDocuments(category: DocumentCategory? = null): Flow<PagingData<DocumentFile>>

    /** Marks the next Paging refresh as an explicit storage refresh. */
    fun requestPagingRefresh()
}
