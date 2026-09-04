package com.droidnova.allfilereader.di

import com.droidnova.allfilereader.data.repository.MediaStoreDocumentRepository
import com.droidnova.allfilereader.data.repository.DataStoreFavoritesRepository
import com.droidnova.allfilereader.data.repository.SafFolderRepository
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FolderRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentModule {
    @Binds @Singleton abstract fun bindDocumentRepository(implementation: MediaStoreDocumentRepository): DocumentRepository
    @Binds @Singleton abstract fun bindFavoritesRepository(implementation: DataStoreFavoritesRepository): FavoritesRepository
    @Binds @Singleton abstract fun bindFolderRepository(implementation: SafFolderRepository): FolderRepository
}
