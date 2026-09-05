package com.droidnova.allfilereader.data.repository

internal data class DocumentSnapshotEvidence(
    val documentIds: Set<String>,
    val rootByDocumentId: Map<String, String>
)

internal data class ScanCoverage(
    val completedRootIds: Set<String>,
    val unavailableRootIds: Set<String>,
    val permissionGrantedForEntireScan: Boolean,
    val completed: Boolean
)

internal fun confirmedDeletedFavoriteIds(
    favoriteIds: Set<String>,
    previous: DocumentSnapshotEvidence?,
    current: DocumentSnapshotEvidence,
    coverage: ScanCoverage
): Set<String> {
    if (previous == null || !coverage.completed || !coverage.permissionGrantedForEntireScan) return emptySet()
    return favoriteIds.filterTo(linkedSetOf()) { id ->
        val previousRoot = previous.rootByDocumentId[id] ?: return@filterTo false
        id in previous.documentIds &&
            previousRoot in coverage.completedRootIds &&
            previousRoot !in coverage.unavailableRootIds &&
            id !in current.documentIds
    }
}
