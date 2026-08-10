package com.droidnova.allfilereader.ui.screens.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HomeTab {
    Recent,
    Bookmarks
}

data class HomeUiState(
    val selectedTab: HomeTab = HomeTab.Recent
)

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onTabSelected(tab: HomeTab) {
        _uiState.update { currentState ->
            currentState.copy(selectedTab = tab)
        }
    }
}
