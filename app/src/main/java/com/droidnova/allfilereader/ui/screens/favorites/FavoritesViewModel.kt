package com.droidnova.allfilereader.ui.screens.favorites

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FavoritesUiState(val isRefreshing: Boolean = false)
class FavoritesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
    fun refresh() { _uiState.value = FavoritesUiState(isRefreshing = false) }
}
