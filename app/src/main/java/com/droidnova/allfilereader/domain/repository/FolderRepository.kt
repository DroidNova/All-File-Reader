package com.droidnova.allfilereader.domain.repository

import com.droidnova.allfilereader.domain.model.SafEntry
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface FolderRepository {
    suspend fun roots(): List<SafEntry>
    suspend fun children(rootPath: String, folderPath: String): List<SafEntry>
    fun pagedEntries(rootPath: String?, folderPath: String?): Flow<PagingData<SafEntry>>
}
class FolderAccessRevokedException(cause: Throwable) : Exception(cause)
