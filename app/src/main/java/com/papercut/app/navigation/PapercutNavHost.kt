package com.papercut.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papercut.app.core.data.DocumentRepository
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.rememberMessageCollector
import com.papercut.app.feature.document.DocumentScreen
import com.papercut.app.feature.editor.EditorScreen
import com.papercut.app.feature.library.LibraryScreen
import com.papercut.app.feature.prompt.PromptsScreen
import com.papercut.app.feature.provider.ProviderScreen
import com.papercut.app.feature.scanner.ScannerScreen
import com.papercut.app.feature.settings.SettingsScreen

/**
 * v2 NavHost: Library (all + per-folder scopes), capture-session Scanner,
 * Editor (draft crop), Document hub, Settings trio. Snackbars are app-level.
 */
@Composable
fun PapercutNavHost(
    navController: NavHostController,
    messages: com.papercut.app.core.design.MessageBus,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = PaperColors.Tile,
                    contentColor = PaperColors.Ink,
                    actionColor = PaperColors.Accent,
                    shape = RoundedCornerShape(PaperRadii.small),
                )
            }
        },
    ) { innerPadding ->
        rememberMessageCollector(messages, snackbarHost,
            androidx.compose.runtime.rememberCoroutineScope())

        NavHost(
            navController = navController,
            startDestination = Route.Library.path,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PaperColors.Canvas),
        ) {
            composable(Route.Library.path) {
                LibraryScreen(
                    scope = null,
                    onOpenDocument = { folder, name ->
                        navController.navigate(Route.Document(folder, name).path)
                    },
                    onScanNew = {
                        navController.navigate(Route.Scanner(DocumentRepository.DEFAULT_FOLDER).path)
                    },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
                    onOpenFolder = { name -> navController.navigate(Route.Folder(name).path) },
                )
            }
            composable(
                Route.PATTERN_FOLDER,
                arguments = listOf(navArgument(Route.ARG_FOLDER) { type = NavType.StringType }),
            ) { entry ->
                val folder = entry.arguments?.getString(Route.ARG_FOLDER)
                    ?: DocumentRepository.DEFAULT_FOLDER
                LibraryScreen(
                    scope = folder,
                    onOpenDocument = { _, name ->
                        navController.navigate(Route.Document(folder, name).path)
                    },
                    onScanNew = { navController.navigate(Route.Scanner(folder).path) },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Route.PATTERN_SCANNER,
                arguments = listOf(
                    navArgument(Route.ARG_FOLDER) { type = NavType.StringType },
                    navArgument(Route.ARG_DOC) { type = NavType.StringType; defaultValue = "-" },
                ),
            ) { entry ->
                val folder = entry.arguments?.getString(Route.ARG_FOLDER)
                    ?: DocumentRepository.DEFAULT_FOLDER
                val doc = entry.arguments?.getString(Route.ARG_DOC)?.takeIf { it != "-" }
                ScannerScreen(
                    folderName = folder,
                    appendToDoc = doc,
                    onBack = { navController.popBackStack() },
                    onEditPage = { index ->
                        navController.navigate(Route.Editor(index).path)
                    },
                    onSaved = { savedFolder, savedName ->
                        // go straight to the document we just saved
                        navController.navigate(Route.Document(savedFolder, savedName).path) {
                            popUpTo(Route.Library.path)
                        }
                    },
                    onDraftKept = { count ->
                        messages.post("Draft kept — $count page(s) unfinished")
                    },
                )
            }
            composable(
                Route.PATTERN_EDITOR,
                arguments = listOf(navArgument(Route.ARG_INDEX) { type = NavType.IntType }),
            ) { entry ->
                EditorScreen(
                    draftIndex = entry.arguments?.getInt(Route.ARG_INDEX) ?: 0,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Route.PATTERN_DOCUMENT,
                arguments = listOf(
                    navArgument(Route.ARG_FOLDER) { type = NavType.StringType },
                    navArgument(Route.ARG_DOC) { type = NavType.StringType },
                ),
            ) { entry ->
                DocumentScreen(
                    folderName = entry.arguments?.getString(Route.ARG_FOLDER)
                        ?: DocumentRepository.DEFAULT_FOLDER,
                    docName = entry.arguments?.getString(Route.ARG_DOC) ?: "",
                    onBack = { navController.popBackStack() },
                    onAddPages = {
                        val f = entry.arguments?.getString(Route.ARG_FOLDER)
                            ?: DocumentRepository.DEFAULT_FOLDER
                        val d = entry.arguments?.getString(Route.ARG_DOC) ?: ""
                        navController.navigate(Route.Scanner(f, d).path)
                    },
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
                arguments = listOf(navArgument(Route.ARG_PROVIDER) { type = NavType.StringType }),
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
