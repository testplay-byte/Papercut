package com.papercut.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.BuildConfig
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScreenHeader
import com.papercut.app.core.design.SmoothSwitch
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * Settings hub: three honest bento sections + behavior switches.
 * Replaces the old flat list: no fake "SwiftScan v4.2.0" footer (real version
 * from BuildConfig), no meaningless alternating icon colors, storage lives with
 * its own section, and every key shown MASKED from encrypted storage.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onOpenPrompts: () -> Unit,
) {
    val vm: SettingsViewModel = appViewModel { c -> SettingsViewModel(c) }
    val settings by vm.settings.collectAsState()
    val rows by vm.providerRows.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.moveRootFolder(uri.toString())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas),
        contentPadding = PaddingValues(PaperGap.l),
        verticalArrangement = Arrangement.spacedBy(PaperGap.m),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val backInter = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier.size(40.dp).pressScale(backInter)
                        .clip(RoundedCornerShape(PaperRadii.small))
                        .background(PaperColors.Tile)
                        .tap(backInter, onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.Ink,
                        modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.padding(start = PaperGap.m)) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge,
                        color = PaperColors.Ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Papercut v${BuildConfig.VERSION_NAME}", fontSize = 12.sp,
                        color = PaperColors.InkSecondary)
                }
            }
        }

        // AI providers tile-grid: one tile per enabled provider
        item {
            SectionTitle(Icons.Filled.Key, "AI providers")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                rows.forEach { row ->
                    val on = row.provider.enabled
                    BentoTile(
                        selected = row.isActive && on,
                        onClick = { onOpenProvider(row.provider.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.provider.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (on) PaperColors.Ink else PaperColors.InkFaint,
                                )
                                StatCaption(
                                    when {
                                        !on -> "disabled"
                                        row.provider.requiresKey.not() -> "no key needed · edit URL"
                                        row.keyCount == 0 -> "no keys yet"
                                        else -> "${row.keyCount} key(s) · ${row.maskedSample ?: ""}"
                                    }
                                )
                            }
                            if (row.isActive && on) {
                                Text(
                                    "ACTIVE",
                                    color = PaperColors.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                )
                            }
                            SmoothSwitch(
                                checked = on,
                                onCheckedChange = { vm.toggleProvider(row.provider.id) },
                            )
                        }
                    }
                }
                // add-provider entry doubles as provider manager
                val addInter = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(addInter)
                        .background(PaperColors.TileSunken, RoundedCornerShape(PaperRadii.tile))
                        .tap(addInter) { onOpenProvider("__new__") }
                        .padding(PaperGap.m),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋ Add / manage providers", color = PaperColors.InkSecondary, fontSize = 14.sp)
                }
            }
        }

        // Prompts tile
        item {
            SectionTitle(Icons.Filled.Tune, "Prompts")
        }
        item {
            BentoTile(onClick = onOpenPrompts, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperGap.m)) {
                    Icon(Icons.Filled.Tune, null, tint = PaperColors.Accent, modifier = Modifier.size(26.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Scan prompts", style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink)
                        StatCaption("Text & Notes")
                    }
                }
            }
        }

        // Behavior switches
        item {
            SectionTitle(Icons.Filled.Bolt, "Behavior")
        }
        item {
            BentoTile(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-digitize after scan", style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink)
                        StatCaption("Digitize right after capture")
                    }
                    SmoothSwitch(checked = settings.autoEnhance, onCheckedChange = { vm.setAutoEnhance(it) })
                }
                Row(
                    modifier = Modifier.padding(top = PaperGap.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Default mode", style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink)
                        StatCaption("In the scanner")
                    }
                    com.papercut.app.core.design.SegmentedPill(
                        options = listOf(ScanMode.TEXT to "Text", ScanMode.NOTES to "Notes"),
                        selected = settings.defaultScanMode,
                        onSelect = { vm.setDefaultScanMode(it) },
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
        }

        // Storage section
        item {
            SectionTitle(Icons.Filled.Folder, "Storage")
        }
        item {
            BentoTile(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperGap.m)) {
                    Icon(Icons.Filled.Folder, null, tint = PaperColors.InkSecondary, modifier = Modifier.size(26.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Scan folder", style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink)
                        StatCaption(
                            settings.rootFolderUri?.let { decodeTreeName(it) } ?: "none chosen",
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Keys are stored encrypted on this device. Scans stay as plain files.",
                fontSize = 12.sp,
                color = PaperColors.InkSecondary,
                modifier = Modifier.padding(top = PaperGap.s),
            )
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = PaperColors.Accent, modifier = Modifier.size(18.dp))
        StatCaption(title)
    }
}

private fun decodeTreeName(uri: String): String = try {
    android.net.Uri.parse(uri).lastPathSegment?.removePrefix("tree;")?.substringAfter(':') ?: uri
} catch (_: Exception) {
    uri
}
