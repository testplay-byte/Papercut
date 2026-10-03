package com.papercut.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.feature.library.FolderScreen
import com.papercut.app.feature.library.LibraryScreen
import com.papercut.app.feature.prompt.PromptsScreen
import com.papercut.app.feature.provider.ProviderScreen
import com.papercut.app.feature.scanner.ScannerScreen
import com.papercut.app.feature.settings.SettingsScreen
import com.papercut.app.feature.viewer.ViewerScreen

/**
 * Single NavHost + shared bento bottom bar.
 * Focus modes (scanner, viewer) hide the bar via one explicit route list —
 * the old app used fragile startsWith() string checks scattered in MainActivity.
 */
@Composable
fun PapercutNavHost(
    navController: NavHostController,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBar = currentRoute == Route.Library.path ||
        currentRoute == Route.PATTERN_FOLDER ||
        currentRoute == Route.Settings.path ||
        currentRoute == Route.Prompts.path ||
        currentRoute == Route.PATTERN_PROVIDER

    Scaffold(
        modifier = modifier,
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(tween(220)) { it },
                exit = slideOutVertically(tween(180)) { it },
            ) {
                val isSettingsSide = currentRoute == Route.Settings.path ||
                    currentRoute == Route.Prompts.path ||
                    currentRoute?.startsWith("provider/") == true
                PaperBottomBar(
                    selected = if (isSettingsSide) 1 else 0,
                    onLibrary = {
                        navController.navigate(Route.Library.path) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onScan = {
                        val folder = backStackEntry?.arguments?.getString(Route.ARG_FOLDER)
                            ?: SettingsRepository.DEFAULT_FOLDER
                        navController.navigate(Route.Scanner(folder).path)
                    },
                    onSettings = {
                        navController.navigate(Route.Settings.path) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Library.path,
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Route.Library.path) {
                LibraryScreen(
                    onOpenFolder = { name -> navController.navigate(Route.Folder(name).path) },
                )
            }
            composable(
                Route.PATTERN_FOLDER,
                arguments = listOf(navArgument(Route.ARG_FOLDER) { defaultValue = SettingsRepository.DEFAULT_FOLDER }),
            ) { entry ->
                val folder = entry.arguments?.getString(Route.ARG_FOLDER)
                    ?: SettingsRepository.DEFAULT_FOLDER
                FolderScreen(
                    folderName = folder,
                    onBack = { navController.popBackStack() },
                    onOpenScan = { scan -> navController.navigate(Route.Viewer(folder, scan).path) },
                    onScanHere = { navController.navigate(Route.Scanner(folder).path) },
                )
            }
            composable(
                Route.PATTERN_SCANNER,
                arguments = listOf(navArgument(Route.ARG_FOLDER) { defaultValue = SettingsRepository.DEFAULT_FOLDER }),
            ) { entry ->
                val folder = entry.arguments?.getString(Route.ARG_FOLDER)
                    ?: SettingsRepository.DEFAULT_FOLDER
                ScannerScreen(
                    folderName = folder,
                    onDone = { navController.popBackStack() },
                    onOpenLatest = { scan ->
                        navController.navigate(Route.Viewer(folder, scan).path) {
                            popUpTo(Route.PATTERN_SCANNER) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Route.PATTERN_VIEWER,
                arguments = listOf(
                    navArgument(Route.ARG_FOLDER) { type = androidx.navigation.NavType.StringType },
                    navArgument(Route.ARG_SCAN) { type = androidx.navigation.NavType.StringType },
                ),
            ) { entry ->
                ViewerScreen(
                    folderName = entry.arguments?.getString(Route.ARG_FOLDER)
                        ?: SettingsRepository.DEFAULT_FOLDER,
                    scanName = entry.arguments?.getString(Route.ARG_SCAN) ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.Settings.path) {
                SettingsScreen(
                    onOpenProvider = { id -> navController.navigate(Route.ProviderSettings(id).path) },
                    onOpenPrompts = { navController.navigate(Route.Prompts.path) },
                )
            }
            composable(
                Route.PATTERN_PROVIDER,
                arguments = listOf(navArgument(Route.ARG_PROVIDER) { type = androidx.navigation.NavType.StringType }),
            ) { entry ->
                ProviderScreen(
                    providerId = entry.arguments?.getString(Route.ARG_PROVIDER) ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.Prompts.path) {
                PromptsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
