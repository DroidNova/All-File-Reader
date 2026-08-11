package com.droidnova.allfilereader.navigation

import androidx.lifecycle.ViewModel
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FileNavigationViewModel @Inject constructor(
    private val repository: DocumentRepository
) : ViewModel() {
    fun remember(document: DocumentFile) {
        repository.rememberDocument(document)
    }
}
