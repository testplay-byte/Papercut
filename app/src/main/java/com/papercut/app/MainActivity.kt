package com.papercut.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.rememberNavController
import com.papercut.app.core.design.PapercutTheme
import com.papercut.app.feature.onboarding.WelcomeSetupScreen
import com.papercut.app.navigation.PapercutNavHost
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // transparent system bars; screens own their safe areas
        val container = (application as PapercutApp).container
        setContent {
            PapercutTheme {
                PapercutAppRoot(container)
            }
        }
    }
}

/**
 * Root gate: no storage folder chosen yet -> onboarding; otherwise the app.
 * (The old app kept this check tangled inside MainActivity's setContent body.)
 */
@Composable
fun PapercutAppRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()

    if (settings.rootFolderUri == null) {
        WelcomeSetupScreen(onFolderPicked = { uri ->
            // persistRootFolder also takes the PERSISTABLE SAF permission
            container.settings.persistRootFolder(uri)
            scope.launch { container.scans.ensureRoot() }
        })
    } else {
        PapercutNavHost(navController = rememberNavController())
    }
}
