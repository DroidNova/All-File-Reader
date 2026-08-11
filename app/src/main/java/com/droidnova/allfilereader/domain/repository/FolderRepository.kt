package com.droidnova.allfilereader.domain.repository

import com.droidnova.allfilereader.domain.model.SafEntry

interface FolderRepository {
    suspend fun roots(): List<SafEntry>
    suspend fun children(rootPath: String, folderPath: String): List<SafEntry>
}
class FolderAccessRevokedException(cause: Throwable) : Exception(cause)
