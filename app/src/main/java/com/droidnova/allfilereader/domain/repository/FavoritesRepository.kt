package com.droidnova.allfilereader.domain.repository

import kotlinx.coroutines.flow.Flow

/** Persists only deterministic document IDs; document names, locations and contents are never stored. */
interface FavoritesRepository {
    val favoriteIds: Flow<Set<String>>
    suspend fun isFavorite(documentId: String): Boolean
    suspend fun add(documentId: String): Result<Unit>
    suspend fun remove(documentId: String): Result<Unit>
    suspend fun toggle(documentId: String): Result<Boolean>
}
