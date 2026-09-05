package com.droidnova.allfilereader.navigation

import androidx.lifecycle.ViewModel
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class FileNavigationViewModel @Inject constructor(
    private val repository: DocumentRepository
) : ViewModel() {
    private val _unsupported = MutableStateFlow<DocumentFile?>(null)
    val unsupported = _unsupported.asStateFlow()
    private val _externalError = MutableStateFlow<ExternalOpenResult?>(null)
    val externalError = _externalError.asStateFlow()
    private var launching = false
    fun remember(document: DocumentFile) {
        repository.rememberDocument(document)
    }
    fun showUnsupported(document: DocumentFile) { _unsupported.value = document; _externalError.value = null; launching=false }
    fun dismissUnsupported() { _unsupported.value = null; launching=false }
    fun beginExternal(): DocumentFile? { if(launching)return null; launching=true; return _unsupported.value }
    fun externalResult(result:ExternalOpenResult) { launching=false; _unsupported.value=null; if(result!=ExternalOpenResult.Launched)_externalError.value=result }
    fun dismissExternalError(){_externalError.value=null}
}
