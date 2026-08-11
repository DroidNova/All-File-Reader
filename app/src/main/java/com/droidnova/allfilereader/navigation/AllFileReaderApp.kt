package com.droidnova.allfilereader.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.droidnova.allfilereader.ui.components.AppBottomNavigation
import com.droidnova.allfilereader.ui.screens.category.CategoryFilesScreen
import com.droidnova.allfilereader.ui.screens.favorites.FavoritesScreen
import com.droidnova.allfilereader.ui.screens.files.FilesScreen
import com.droidnova.allfilereader.ui.screens.folders.FoldersScreen
import com.droidnova.allfilereader.ui.screens.home.HomeScreen
import com.droidnova.allfilereader.ui.screens.settings.SettingsScreen

@Composable fun AllFileReaderApp(){val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val current=entry?.destination;val root=AppDestination.entries.any{d->current?.hierarchy?.any{it.hasRoute(d.routeClass)}==true};Scaffold(Modifier.fillMaxSize(),bottomBar={if(root)AppBottomNavigation(AppDestination.entries,{d->current?.hierarchy?.any{it.hasRoute(d.routeClass)}==true}){d->nav.navigate(d.route){popUpTo(nav.graph.findStartDestination().id){saveState=true};launchSingleTop=true;restoreState=true}}}){pad->NavHost(nav,HomeRoute,Modifier.fillMaxSize().padding(pad)){composable<HomeRoute>{HomeScreen({nav.navigate(CategoryFilesRoute(it))},{nav.navigate(DirectoriesRoute)},{nav.navigate(FavoritesRoute)})};composable<RecentRoute>{FilesScreen()};composable<SettingsRoute>{SettingsScreen()};composable<CategoryFilesRoute>{CategoryFilesScreen{nav.popBackStack()}};composable<DirectoriesRoute>{FoldersScreen{nav.popBackStack()}};composable<FavoritesRoute>{FavoritesScreen{nav.popBackStack()}}}}}
