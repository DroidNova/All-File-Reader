package com.droidnova.allfilereader.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.ui.components.AppBottomNavigation
import com.droidnova.allfilereader.ui.screens.category.CategoryFilesScreen
import com.droidnova.allfilereader.ui.screens.favorites.FavoritesScreen
import com.droidnova.allfilereader.ui.screens.files.FilesScreen
import com.droidnova.allfilereader.ui.screens.folders.FoldersScreen
import com.droidnova.allfilereader.ui.screens.home.HomeScreen
import com.droidnova.allfilereader.ui.screens.settings.SettingsScreen
import com.droidnova.allfilereader.ui.screens.pdf.PdfReaderScreen
import kotlinx.coroutines.launch

@Composable
fun AllFileReaderApp(fileNavigationViewModel: FileNavigationViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.reader_unavailable)
    val laterMessages = mapOf(
        DocumentCategory.Word to stringResource(R.string.word_reader_later),
        DocumentCategory.Excel to stringResource(R.string.excel_reader_later),
        DocumentCategory.PowerPoint to stringResource(R.string.ppt_reader_later),
        DocumentCategory.Text to stringResource(R.string.txt_reader_later),
        DocumentCategory.Folder to stringResource(R.string.reader_unavailable),
        DocumentCategory.Other to stringResource(R.string.reader_unavailable)
    )
    val onDocumentClick: (DocumentFile) -> Unit = { document ->
        fileNavigationViewModel.remember(document)
        if (document.category == DocumentCategory.Pdf) {
            navController.navigate(PdfReaderRoute(document.id))
        } else {
            val message = laterMessages[document.category] ?: unavailableMessage
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        }
    }
    val isRootDestination = AppDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any {
            it.hasRoute(destination.routeClass)
        } == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isRootDestination) {
                AppBottomNavigation(
                    destinations = AppDestination.entries,
                    isSelected = { destination ->
                        currentDestination?.hierarchy?.any {
                            it.hasRoute(destination.routeClass)
                        } == true
                    },
                    onDestinationSelected = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onCategorySelected = { categoryId ->
                        navController.navigate(CategoryFilesRoute(categoryId))
                    },
                    onDirectoriesSelected = {
                        navController.navigate(DirectoriesRoute)
                    },
                    onFavoritesSelected = {
                        navController.navigate(FavoritesRoute)
                    }
                )
            }
            composable<RecentRoute> {
                FilesScreen(onDocumentClick = onDocumentClick)
            }
            composable<SettingsRoute> {
                SettingsScreen()
            }
            composable<CategoryFilesRoute> {
                CategoryFilesScreen(
                    onBack = { navController.popBackStack() },
                    onDocumentClick = onDocumentClick
                )
            }
            composable<DirectoriesRoute> {
                FoldersScreen(
                    onNavigateHome = { navController.popBackStack() },
                    onDocumentClick = onDocumentClick
                )
            }
            composable<FavoritesRoute> {
                FavoritesScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable<PdfReaderRoute> {
                PdfReaderScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
