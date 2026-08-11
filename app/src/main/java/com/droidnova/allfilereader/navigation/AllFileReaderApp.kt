package com.droidnova.allfilereader.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.droidnova.allfilereader.ui.components.AppBottomNavigation
import com.droidnova.allfilereader.ui.screens.category.CategoryFilesScreen
import com.droidnova.allfilereader.ui.screens.favorites.FavoritesScreen
import com.droidnova.allfilereader.ui.screens.files.FilesScreen
import com.droidnova.allfilereader.ui.screens.folders.FoldersScreen
import com.droidnova.allfilereader.ui.screens.home.HomeScreen
import com.droidnova.allfilereader.ui.screens.settings.SettingsScreen

@Composable
fun AllFileReaderApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isRootDestination = AppDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any {
            it.hasRoute(destination.routeClass)
        } == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                FilesScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen()
            }
            composable<CategoryFilesRoute> {
                CategoryFilesScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable<DirectoriesRoute> {
                FoldersScreen(
                    onNavigateHome = { navController.popBackStack() }
                )
            }
            composable<FavoritesRoute> {
                FavoritesScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
