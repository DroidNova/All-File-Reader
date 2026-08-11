package com.droidnova.allfilereader.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.SafEntry
import com.droidnova.allfilereader.domain.repository.FolderAccessRevokedException
import com.droidnova.allfilereader.domain.repository.FolderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : FolderRepository {
    override suspend fun roots(): List<SafEntry> = withContext(Dispatchers.IO) {
        rootFiles().mapIndexedNotNull { index, file ->
            if (!file.exists() || !file.canRead()) null else file.toEntry(
                if (index == 0) "Internal shared storage" else file.name.ifBlank { "External storage" }
            )
        }
    }

    override suspend fun children(rootPath: String, folderPath: String): List<SafEntry> = withContext(Dispatchers.IO) {
        try {
            val root = File(rootPath).canonicalFile
            val folder = File(folderPath).canonicalFile
            if (folder.path != root.path && !folder.path.startsWith(root.path + File.separator)) {
                throw SecurityException("Path is outside storage root")
            }
            if (!folder.isDirectory || !folder.canRead() || restricted(folder.path)) {
                throw SecurityException("Directory is inaccessible")
            }
            folder.listFiles().orEmpty().asSequence()
                .filterNot { it.isHidden }
                .filter { it.isDirectory || DocumentClassifier.classify(null, DocumentClassifier.extensionOf(it.name)).let { type ->
                    type != com.droidnova.allfilereader.domain.model.DocumentCategory.Other
                } }
                .map { it.toEntry() }
                .sortedWith(compareByDescending<SafEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                .toList()
        } catch (cancellation: CancellationException) { throw cancellation }
        catch (exception: Exception) { throw FolderAccessRevokedException(exception) }
    }

    private fun rootFiles(): List<File> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
    } else buildList {
        add(Environment.getExternalStorageDirectory())
        context.getExternalFilesDirs(null).mapNotNull { it?.parentFile?.parentFile?.parentFile?.parentFile }.forEach(::add)
    }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

    private fun File.toEntry(label: String = name): SafEntry = SafEntry(
        id = runCatching { canonicalPath }.getOrDefault(absolutePath), displayName = label,
        uri = runCatching { canonicalPath }.getOrDefault(absolutePath), isDirectory = isDirectory,
        mimeType = null, sizeBytes = if (isFile) length() else null, lastModifiedEpochMillis = lastModified()
    )
    private fun restricted(path: String) = path.contains("/Android/data") || path.contains("/Android/obb")
}
