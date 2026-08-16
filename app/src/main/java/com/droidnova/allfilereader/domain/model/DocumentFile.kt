package com.droidnova.allfilereader.domain.model

data class DocumentFile(
    val id: String,
    val displayName: String,
    val uri: String,
    val mimeType: String?,
    val extension: String?,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val category: DocumentCategory,
    val isBookmarked: Boolean
)

enum class DocumentCategory {
    Pdf,
    Word,
    Excel,
    PowerPoint,
    Text,
    Folder,
    Other
}
