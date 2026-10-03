package com.papercut.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.papercut.app.core.data.model.LibraryFolder
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScreenHeader
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.StatNumber
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * Library home: header + two bento stat tiles + folder tile grid.
 * Fixes vs old app: stats derive from data (no fake numbers), all IO off-main
 * inside the repository, and folder management via one clean dialog flow
 * (old: dead search bar, fixed-width cards overflowing on narrow screens).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenFolder: (String) -> Unit,
    onScanHere: () -> Unit,
) {
    val vm: LibraryViewModel = appViewModel { c -> LibraryViewModel(c) }
    val folders by vm.folders.collectAsState()
    val total by vm.totalScans.collectAsState()
    val digitized by vm.digitizedScans.collectAsState()
    val message by vm.message.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<LibraryFolder?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = PaperGap.l, end = PaperGap.l, top = PaperGap.l, bottom = 110.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(PaperGap.m),
            verticalArrangement = Arrangement.spacedBy(PaperGap.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                ScreenHeader("Library", "Every scan, kept as plain files you own")
            }
            item {
                BentoTile {
                    StatNumber(total.toString(), color = PaperColors.Accent)
                    StatCaption("Total scans")
                }
            }
            item {
                BentoTile {
                    StatNumber("$digitized", color = PaperColors.Success)
                    StatCaption("Digitized")
                }
            }
            item {
                // "new folder" tile lives here, visible and honest
                val inter = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .aspectRatio(1.15f)
                        .fillMaxWidth()
                        .pressScale(inter)
                        .clip(RoundedCornerShape(PaperRadii.tile))
                        .background(PaperColors.TileSunken)
                        .tap(inter) { showCreate = true },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Add, "New folder", tint = PaperColors.InkSecondary, modifier = Modifier.size(30.dp))
                    Text("New folder", color = PaperColors.InkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(folders, key = { it.name }) { folder ->
                FolderTile(
                    folder = folder,
                    onOpen = { onOpenFolder(folder.name) },
                    onManage = { manageTarget = folder },
                )
            }
        }

        // floating scan button (library-level quick scan into Default)
        val scanInter = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PaperGap.l, bottom = 88.dp)
                .size(56.dp)
                .pressScale(scanInter)
                .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                .tap(scanInter, onScanHere),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "Scan", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                vm.createFolder(name) { showCreate = false }
            },
        )
    }

    manageTarget?.let { folder ->
        var renameMode by remember(folder.name) { mutableStateOf(false) }
        when {
            renameMode -> NameDialog(
                title = "Rename folder",
                initial = folder.name,
                confirmLabel = "Rename",
                onDismiss = { renameMode = false; manageTarget = null },
                onConfirm = { newName ->
                    vm.renameFolder(folder.name, newName) { renameMode = false; manageTarget = null }
                },
            )
            else -> AlertDialog(
                onDismissRequest = { manageTarget = null },
                title = { Text(folder.name) },
                text = {
                    Text("${folder.scanCount} scans · ${folder.digitizedCount} digitized")
                },
                confirmButton = {
                    TextButton(onClick = { renameMode = true }) { Text("Rename") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder.name != com.papercut.app.core.data.SettingsRepository.DEFAULT_FOLDER) {
                            TextButton(
                                onClick = {
                                    vm.deleteFolder(folder.name) { manageTarget = null }
                                },
                            ) { Text("Delete", color = PaperColors.Error) }
                        }
                        TextButton(onClick = { manageTarget = null }) { Text("Close") }
                    }
                },
            )
        }
    }

    // transient error/success note
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2500)
            vm.consumeMessage()
        }
    }
    message?.let {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .background(PaperColors.Ink, RoundedCornerShape(PaperRadii.small))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) { Text(it, color = PaperColors.Canvas, fontSize = 13.sp) }
    }
}

/** One folder = one bento tile: cover image, big count, digitized fraction. */
@Composable
private fun FolderTile(folder: LibraryFolder, onOpen: () -> Unit, onManage: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .aspectRatio(1.15f)
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(PaperRadii.tile))
            .background(PaperColors.Tile)
            .combinedTap(interaction, onOpen, onManage),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PaperColors.TileSunken),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverUri != null) {
                AsyncImage(
                    model = folder.coverUri,
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = if (folder.name == com.papercut.app.core.data.SettingsRepository.DEFAULT_FOLDER)
                        PaperColors.Accent else PaperColors.InkFaint,
                    modifier = Modifier.size(34.dp),
                )
            }
            if (folder.name == com.papercut.app.core.data.SettingsRepository.DEFAULT_FOLDER) {
                StatCaption(
                    "Default",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(PaperColors.Tile, RoundedCornerShape(PaperRadii.pill))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = PaperColors.Ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "${folder.digitizedCount}/${folder.scanCount} done",
                    fontSize = 12.sp,
                    color = PaperColors.InkSecondary,
                )
            }
            StatNumber(folder.scanCount.toString(), color = PaperColors.Accent)
        }
    }
}

/** tap = open, long-press = manage. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedTap(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
    onLongClick = onLongClick,
)

/** Single reusable name dialog (the old app's best pattern — kept, cleaned). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Folder name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
