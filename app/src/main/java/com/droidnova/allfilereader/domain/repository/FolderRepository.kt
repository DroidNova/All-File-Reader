package com.droidnova.allfilereader.domain.repository

import com.droidnova.allfilereader.domain.model.SafEntry

interface FolderRepository {
    suspend fun persistedFolders(): List<SafEntry>
    suspend fun children(folderUri: String): List<SafEntry>
    suspend fun persistReadPermission(folderUri: String): Boolean
}

class FolderAccessRevokedException(cause: Throwable) : Exception(cause)
