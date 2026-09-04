package com.droidnova.allfilereader.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

private val Context.favoritesDataStore by preferencesDataStore(name = "document_favorites")

internal enum class FavoriteChange { Add, Remove, Toggle }
internal fun applyFavoriteChange(current: Set<String>, id: String, change: FavoriteChange): Set<String> = when (change) {
    FavoriteChange.Add -> current + id
    FavoriteChange.Remove -> current - id
    FavoriteChange.Toggle -> if (id in current) current - id else current + id
}

@Singleton
class DataStoreFavoritesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : FavoritesRepository {
    private val idsKey = stringSetPreferencesKey("favorite_document_ids")
    private val operations = ConcurrentHashMap.newKeySet<String>()
    override val favoriteIds: Flow<Set<String>> = context.favoritesDataStore.data
        .map { preferences -> preferences[idsKey]?.toSet().orEmpty() }
        .catch { error ->
            trace("read", false, "unavailable", null, error as? Exception ?: RuntimeException(error))
            emit(emptySet())
        }

    override suspend fun isFavorite(documentId: String): Boolean = documentId in favoriteIds.first()
    override suspend fun add(documentId: String) = update("add", documentId) { applyFavoriteChange(it, documentId, FavoriteChange.Add) }.map { }
    override suspend fun remove(documentId: String) = update("remove", documentId) { applyFavoriteChange(it, documentId, FavoriteChange.Remove) }.map { }

    override suspend fun toggle(documentId: String): Result<Boolean> {
        if (!operations.add(documentId)) return Result.failure(FavoriteOperationInProgressException())
        return try {
            var selected = false
            context.favoritesDataStore.edit { preferences ->
                val current = preferences[idsKey]?.toSet().orEmpty()
                selected = documentId !in current
                preferences[idsKey] = applyFavoriteChange(current, documentId, FavoriteChange.Toggle)
            }
            trace("toggle", true, documentId, favoriteIds.first().size, null)
            Result.success(selected)
        } catch (error: Exception) {
            trace("toggle", false, documentId, null, error)
            Result.failure(error)
        } finally {
            operations.remove(documentId)
        }
    }

    private suspend fun update(
        operation: String,
        documentId: String,
        transform: (Set<String>) -> Set<String>
    ): Result<Set<String>> {
        if (!operations.add(documentId)) return Result.failure(FavoriteOperationInProgressException())
        return try {
            var updated: Set<String> = emptySet()
            context.favoritesDataStore.edit { preferences ->
                updated = transform(preferences[idsKey]?.toSet().orEmpty())
                preferences[idsKey] = updated
            }
            trace(operation, true, documentId, updated.size, null)
            Result.success(updated)
        } catch (error: Exception) {
            trace(operation, false, documentId, null, error)
            Result.failure(error)
        } finally {
            operations.remove(documentId)
        }
    }

    private fun trace(operation: String, success: Boolean, id: String, count: Int?, error: Exception?) {
        if (!BuildConfig.DEBUG) return
        val safeId = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(4)
            .joinToString("") { "%02x".format(it) }
        Log.d("Favorites", "operation=$operation success=$success idHash=$safeId count=${count ?: -1} error=${error?.javaClass?.simpleName ?: "none"}")
    }
}

class FavoriteOperationInProgressException : IllegalStateException("Favorite update already in progress")
