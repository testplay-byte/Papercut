package com.papercut.app.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.R
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap

/**
 * First-run screen: pick the storage folder. Minimal by design — mark,
 * one line, one tile.
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PaperGap.l),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_papercut_mark),
                contentDescription = "Papercut",
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "Papercut",
                style = MaterialTheme.typography.headlineMedium,
                color = PaperColors.Ink,
            )
            Text(
                text = "Pages in. Digital twins out.",
                style = MaterialTheme.typography.bodyMedium,
                color = PaperColors.InkSecondary,
                textAlign = TextAlign.Center,
            )

            val inter = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .padding(top = PaperGap.s)
                    .pressScale(inter)
                    .clip(RoundedCornerShape(PaperRadii.tile))
                    .background(PaperColors.Tile)
                    .border(1.dp, PaperColors.TileBorder, RoundedCornerShape(PaperRadii.tile))
                    .tap(inter) { picker.launch(null) }
                    .padding(PaperGap.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Choose storage folder",
                    style = MaterialTheme.typography.titleMedium,
                    color = PaperColors.Ink,
                )
                Text(
                    text = "Papercut creates a \"Papercut\" folder inside it.",
                    fontSize = 12.sp,
                    color = PaperColors.InkSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
