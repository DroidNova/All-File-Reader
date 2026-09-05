package com.droidnova.allfilereader.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.reader.DocumentReaderDestination
import com.droidnova.allfilereader.domain.reader.DocumentReaderResolver
import com.droidnova.allfilereader.domain.reader.DocumentOpenResult
import com.droidnova.allfilereader.ui.components.AppBottomNavigation
import com.droidnova.allfilereader.ui.components.MandatoryStoragePermissionSheet
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import com.droidnova.allfilereader.ui.screens.category.CategoryFilesScreen
import com.droidnova.allfilereader.ui.screens.favorites.FavoritesScreen
import com.droidnova.allfilereader.ui.screens.files.FilesScreen
import com.droidnova.allfilereader.ui.screens.folders.FoldersScreen
import com.droidnova.allfilereader.ui.screens.home.HomeScreen
import com.droidnova.allfilereader.ui.screens.settings.SettingsScreen
import com.droidnova.allfilereader.ui.screens.pdf.PdfReaderScreen
import com.droidnova.allfilereader.ui.screens.txt.TxtReaderScreen
import com.droidnova.allfilereader.ui.screens.word.WordReaderScreen
import com.droidnova.allfilereader.ui.screens.excel.ExcelReaderScreen
import com.droidnova.allfilereader.ui.screens.powerpoint.PowerPointReaderScreen
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AllFileReaderApp(
    fileNavigationViewModel: FileNavigationViewModel = hiltViewModel(),
    storageAccessViewModel: StorageAccessViewModel = hiltViewModel(),
    incomingDocumentViewModel: IncomingDocumentViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.reader_unavailable)
    val accessState by storageAccessViewModel.state.collectAsStateWithLifecycle()
    val incomingState by incomingDocumentViewModel.state.collectAsStateWithLifecycle()
    val requestAccess = rememberStorageAccessRequest(storageAccessViewModel::recheck)
    val unsupported by fileNavigationViewModel.unsupported.collectAsStateWithLifecycle()
    val externalError by fileNavigationViewModel.externalError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        storageAccessViewModel.recheck()
        onPauseOrDispose { }
    }
    LaunchedEffect(accessState) {
        if (accessState != StorageAccessState.Granted && currentDestination?.hasRoute<HomeRoute>() == false) {
            navController.navigate(HomeRoute) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    val onDocumentClick: (DocumentFile) -> Unit = { document ->
        fileNavigationViewModel.remember(document)
        when (val result = DocumentReaderResolver.resolve(document)) {
            is DocumentOpenResult.Internal -> when(result.destination) {
            DocumentReaderDestination.Pdf -> navController.navigate(PdfReaderRoute(document.id)) {
                launchSingleTop = true
            }
            DocumentReaderDestination.PlainText -> navController.navigate(TxtReaderRoute(document.id)) {
                launchSingleTop = true
            }
            DocumentReaderDestination.Docx ->
                navController.navigate(WordReaderRoute(document.id)) { launchSingleTop = true }
            DocumentReaderDestination.Spreadsheet ->
                navController.navigate(ExcelReaderRoute(document.id)) { launchSingleTop = true }
            DocumentReaderDestination.PowerPoint -> navController.navigate(PowerPointReaderRoute(document.id)) { launchSingleTop = true }
            }
            DocumentOpenResult.LegacyPowerPoint -> navController.navigate(PowerPointReaderRoute(document.id)) { launchSingleTop=true }
            DocumentOpenResult.Unsupported -> fileNavigationViewModel.showUnsupported(document)
            DocumentOpenResult.AccessFailure, DocumentOpenResult.FormatMismatch -> coroutineScope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        }
    }
    val isRootDestination = AppDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any {
            it.hasRoute(destination.routeClass)
        } == true
    }
    LaunchedEffect(incomingState, accessState) {
        if (incomingState is IncomingUiState.PendingPermission) {
            incomingDocumentViewModel.processPending(accessState == StorageAccessState.Granted)
            return@LaunchedEffect
        }
        val open = incomingState as? IncomingUiState.Open ?: return@LaunchedEffect
        if (accessState != StorageAccessState.Granted) return@LaunchedEffect
        onDocumentClick(open.document)
        incomingDocumentViewModel.consumed()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isRootDestination && accessState == StorageAccessState.Granted) {
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
        if (accessState == StorageAccessState.Granted) NavHost(
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
                    }, onSearch = {
                        fileNavigationViewModel.requestRecentSearch()
                        navController.navigate(RecentRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                    onBack = { navController.popBackStack() },
                    onDocumentClick = onDocumentClick
                )
            }
            composable<PdfReaderRoute> {
                PdfReaderScreen(onBack = { navController.popBackStack() })
            }
            composable<TxtReaderRoute> {
                TxtReaderScreen(onBack = { navController.popBackStack() })
            }
            composable<WordReaderRoute> {
                WordReaderScreen(onBack = { navController.popBackStack() })
            }
            composable<ExcelReaderRoute> {
                ExcelReaderScreen(onBack = { navController.popBackStack() })
            }
            composable<PowerPointReaderRoute> {
                PowerPointReaderScreen(onBack = { navController.popBackStack() })
            }
        } else Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text(stringResource(R.string.app_name)) }) }
        ) { gatedPadding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(gatedPadding))
            if (accessState != StorageAccessState.Checking) {
                MandatoryStoragePermissionSheet(
                    requesting = accessState == StorageAccessState.Requesting,
                    onAllow = {
                        if (storageAccessViewModel.beginRequest()) {
                            try {
                                requestAccess()
                            } catch (_: RuntimeException) {
                                storageAccessViewModel.launchDispatched()
                            }
                        }
                    }
                )
            }
        }
    }
    val failure = incomingState as? IncomingUiState.Failure
    if (failure != null && accessState == StorageAccessState.Granted) {
        val (title, message) = when (failure.reason) {
            IncomingError.Unsupported, IncomingError.AmbiguousUri -> R.string.incoming_unsupported_title to R.string.incoming_unsupported_message
            IncomingError.FormatMismatch -> R.string.incoming_mismatch_title to R.string.incoming_mismatch_message
            IncomingError.AccessDenied -> R.string.incoming_access_title to R.string.incoming_access_message
            IncomingError.MissingUri -> R.string.incoming_missing_title to R.string.incoming_missing_message
        }
        AlertDialog(
            onDismissRequest = incomingDocumentViewModel::dismissError,
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = incomingDocumentViewModel::dismissError) { Text(stringResource(R.string.back)) } }
        )
    }
    unsupported?.let { document -> AlertDialog(onDismissRequest=fileNavigationViewModel::dismissUnsupported,
        title={Text(stringResource(R.string.unsupported_file_title))},text={Text(stringResource(R.string.unsupported_file_message))},
        confirmButton={TextButton(onClick={fileNavigationViewModel.beginExternal()?.let { fileNavigationViewModel.externalResult(UnsupportedFileExternalOpener.open(context,it)) }}){Text(stringResource(R.string.open_with_another_app))}},
        dismissButton={TextButton(onClick=fileNavigationViewModel::dismissUnsupported){Text(stringResource(R.string.cancel))}}) }
    externalError?.let { result -> AlertDialog(onDismissRequest=fileNavigationViewModel::dismissExternalError,
        title={Text(stringResource(if(result==ExternalOpenResult.NoCompatibleApp)R.string.no_compatible_app_title else R.string.external_preparation_title))},
        text={Text(stringResource(if(result==ExternalOpenResult.NoCompatibleApp)R.string.no_compatible_app_message else R.string.external_preparation_message))},
        confirmButton={TextButton(onClick=fileNavigationViewModel::dismissExternalError){Text(stringResource(android.R.string.ok))}}) }
}
