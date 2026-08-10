package com.droidnova.allfilereader.di

import android.content.ContentResolver
import android.content.Context
import com.droidnova.allfilereader.data.repository.MediaStoreDocumentRepository
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.data.repository.SafFolderRepository
import com.droidnova.allfilereader.domain.repository.FolderRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentModule {
    @Binds
    @Singleton
    abstract fun bindDocumentRepository(
        implementation: MediaStoreDocumentRepository
    ): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(implementation: SafFolderRepository): FolderRepository

    companion object {
        @Provides
        fun provideContentResolver(
            @ApplicationContext context: Context
        ): ContentResolver = context.contentResolver
    }
}
