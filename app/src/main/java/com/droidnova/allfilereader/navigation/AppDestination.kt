package com.droidnova.allfilereader.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.droidnova.allfilereader.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object RecentRoute
@Serializable data object SettingsRoute
@Serializable data class CategoryFilesRoute(val categoryId:String)
@Serializable data object DirectoriesRoute
@Serializable data object FavoritesRoute
@Serializable data class PdfReaderRoute(val documentId: String)

enum class AppDestination(val route:Any,val routeClass:KClass<*>,@param:StringRes val labelResId:Int,val icon:ImageVector){Home(HomeRoute,HomeRoute::class,R.string.home,Icons.Default.Home),Recent(RecentRoute,RecentRoute::class,R.string.recent,Icons.Default.History),Settings(SettingsRoute,SettingsRoute::class,R.string.settings,Icons.Default.Settings)}
