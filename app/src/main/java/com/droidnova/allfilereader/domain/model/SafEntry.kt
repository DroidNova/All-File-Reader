package com.droidnova.allfilereader.domain.model

data class SafEntry(
    val id: String,
    val displayName: String,
    val uri: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?
)
