package com.papercut.app.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.core.data.model.LibraryFolder
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.BrandHeader
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.StatNumber
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * Library home — dark, minimal bento:
 * brand header, one compact stats strip, folder cover tiles.
 * Long-press a folder to rename/delete (Default is protected in the repository).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenFolder: (String) -> Unit,
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
            columns = GridCells.Adaptive(minSize = 158.dp),
            contentPadding = PaddingValues(
                start = PaperGap.l, end = PaperGap.l, top = PaperGap.m, bottom = 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
            verticalArrangement = Arrangement.spacedBy(PaperGap.s),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandHeader("Papercut", modifier = Modifier.weight(1f))
                    val newInter = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .pressScale(newInter)
                            .clip(RoundedCornerShape(PaperRadii.small))
                            .background(PaperColors.Tile)
                            .tap(newInter) { showCreate = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Add, "New folder",
                            tint = PaperColors.Accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // compact stats strip: one tile, two tabular numbers
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    BentoTile(modifier = Modifier.weight(1f)) {
                        StatNumber("$total")
                        StatCaption("scans")
                    }
                    BentoTile(modifier = Modifier.weight(1f)) {
                        StatNumber("$digitized", color = PaperColors.Accent)
                        StatCaption("digitized")
                    }
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

        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.papercut.app.R.drawable.ic_papercut_mark),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        "No folders yet",
                        color = PaperColors.InkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = PaperGap.m),
                    )
                    val cta = remember { MutableInteractionSource() }
                    Text(
                        "Create one",
                        color = PaperColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .pressScale(cta)
                            .tap(cta) { showCreate = true }
                            .padding(8.dp),
                    )
                }
            }
        }

        // transient message
        message?.let {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 100.dp)
                        .background(PaperColors.Tile, RoundedCornerShape(PaperRadii.small))
                        .border(1.dp, PaperColors.TileBorder, RoundedCornerShape(PaperRadii.small))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text(it, color = PaperColors.Ink, fontSize = 13.sp) }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name -> vm.createFolder(name) { showCreate = false } },
        )
    }

    manageTarget?.let { folder ->
        var renameMode by remember(folder.name) { mutableStateOf(false) }
        when {
            renameMode -> NameDialog(
                title = "Rename",
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
                text = { Text("${folder.scanCount} scans") },
                confirmButton = {
                    TextButton(onClick = { renameMode = true }) { Text("Rename") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder.name != SettingsRepository.DEFAULT_FOLDER) {
                            TextButton(
                                onClick = { vm.deleteFolder(folder.name) { manageTarget = null } },
                            ) { Text("Delete", color = PaperColors.Error) }
                        }
                        TextButton(onClick = { manageTarget = null }) { Text("Close") }
                    }
                },
            )
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2500)
            vm.consumeMessage()
        }
    }
}

/** Folder tile: cover photo on top, name + count below. Clean, no chrome. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: LibraryFolder, onOpen: () -> Unit, onManage: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .aspectRatio(0.92f)
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(PaperRadii.tile))
            .background(PaperColors.Tile)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
                onLongClick = onManage,
            ),
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
                Text(
                    folder.name.take(1).uppercase(),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (folder.name == SettingsRepository.DEFAULT_FOLDER)
                        PaperColors.Accent else PaperColors.InkFaint,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = PaperColors.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${folder.scanCount}",
                color = PaperColors.InkSecondary,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

/** Reusable name dialog. */
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
                placeholder = { Text("Name") },
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
