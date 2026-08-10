package com.droidnova.allfilereader.domain.repository

import com.droidnova.allfilereader.domain.model.DocumentFile
import kotlinx.coroutines.flow.StateFlow

interface DocumentRepository {
    val documents: StateFlow<List<DocumentFile>>

    suspend fun getDocuments(
        includeImages: Boolean,
        forceRefresh: Boolean = false
    ): List<DocumentFile>
}
