package com.papercut.app.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.StatCaption

/**
 * First-run screen: pick the folder where Papercut stores scans.
 * Explains WHY (scans live outside app storage so the user owns their files) —
 * the old app dumped you on a bare folder picker with zero context.
 * The picker grants PERSISTABLE read/write permission here; the repository
 * stores the uri. See SettingsRepository.persistRootFolder().
 */
@Composable
fun WelcomeSetupScreen(onFolderPicked: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onFolderPicked(uri.toString())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas)
            .padding(PaperGap.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(PaperColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✂", fontSize = 40.sp, color = androidx.compose.ui.graphics.Color.White)
            }
            Text(
                text = "Welcome to Papercut",
                style = MaterialTheme.typography.headlineMedium,
                color = PaperColors.Ink,
                modifier = Modifier.padding(top = PaperGap.l),
            )
            Text(
                text = "Scan pages, and your AI turns them into pixel-perfect digital twins — HTML you can read, search and print.",
                style = MaterialTheme.typography.bodyMedium,
                color = PaperColors.InkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = PaperGap.s),
            )

            BentoTile(
                modifier = Modifier.padding(top = PaperGap.xl),
                onClick = { picker.launch(null) },
            ) {
                StatCaption("Step 1 of 3")
                Text(
                    text = "Choose storage folder",
                    style = MaterialTheme.typography.titleLarge,
                    color = PaperColors.Ink,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                Text(
                    text = "A \"Papercut\" folder is created inside it. Your scans are yours — plain files you can open from any app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperColors.InkSecondary,
                )
            }
        }
    }
}
