package com.droidnova.allfilereader.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.droidnova.allfilereader.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object HomeRoute

@Serializable
data object FilesRoute

@Serializable
data object SettingsRoute

enum class AppDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val labelResId: Int,
    val icon: ImageVector
) {
    Home(HomeRoute, HomeRoute::class, R.string.home, Icons.Default.Home),
    Files(FilesRoute, FilesRoute::class, R.string.files, Icons.Default.Folder),
    Settings(SettingsRoute, SettingsRoute::class, R.string.settings, Icons.Default.Settings)
}
