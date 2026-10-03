package com.papercut.app.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.papercut.app.core.data.model.ScanItem
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScanStatus
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.StatNumber
import com.papercut.app.core.design.StatusBadge
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * One folder's scans: bento grid, one live StatusBadge per card (fed by the
 * queue's single statuses map — never stacked overlays like the old viewer).
 * Long-press = delete. The old multi-select batch mode was dropped: it added a
 * second interaction layer for little value at hobby scale; can come back if wanted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    folderName: String,
    onBack: () -> Unit,
    onOpenScan: (String) -> Unit,
    onScanHere: () -> Unit,
) {
    val vm: FolderViewModel = appViewModel { c -> FolderViewModel(c, folderName) }
    val scans by vm.scans.collectAsState()
    val statuses by vm.statuses.collectAsState()
    var deleteTarget by remember { mutableStateOf<ScanItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PaperGap.m, vertical = PaperGap.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pressScale(backInter)
                    .clip(RoundedCornerShape(PaperRadii.small))
                    .background(PaperColors.Tile)
                    .tap(backInter, onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.Ink, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = PaperGap.m)) {
                Text(folderName, style = MaterialTheme.typography.titleLarge, color = PaperColors.Ink, fontWeight = FontWeight.Bold)
                StatCaption("$folderName · ${scans.size} scans")
            }
            // bento mini-stats
            val done = scans.count { it.htmlUri != null }
            BentoTile(modifier = Modifier.width(86.dp)) {
                StatNumber("$done", color = PaperColors.Success)
                StatCaption("done")
            }
        }

        if (scans.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Image, null, tint = PaperColors.InkFaint, modifier = Modifier.size(40.dp))
                    Text("Nothing scanned here yet", color = PaperColors.InkSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = PaperGap.s))
                    val cta = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .padding(top = PaperGap.m)
                            .pressScale(cta)
                            .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                            .tap(cta, onScanHere)
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) { Text("Start scanning", color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = PaperGap.l, end = PaperGap.l, top = PaperGap.s, bottom = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(PaperGap.m),
                verticalArrangement = Arrangement.spacedBy(PaperGap.m),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(scans, key = { it.name }) { scan ->
                    ScanTile(
                        scan = scan,
                        status = statuses["${scan.folder}/${scan.name}"]?.status ?: ScanStatus.Plain,
                        onOpen = { onOpenScan(scan.name) },
                        onDelete = { deleteTarget = scan },
                    )
                }
            }
        }
    }

    deleteTarget?.let { scan ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete scan?") },
            text = { Text("${scan.name} and its digitized versions are removed from disk.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScan(scan)
                    deleteTarget = null
                }) { Text("Delete", color = PaperColors.Error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanTile(scan: ScanItem, status: ScanStatus, onOpen: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .aspectRatio(0.82f)
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(PaperRadii.tile))
            .background(PaperColors.Tile)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
                onLongClick = onDelete,
            ),
    ) {
        AsyncImage(
            model = scan.imageUri,
            contentDescription = scan.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        StatusBadge(
            status,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
        if (scan.backupUri != null && status == ScanStatus.Plain) {
            StatCaption(
                "backup",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(PaperColors.Tile.copy(alpha = 0.9f), RoundedCornerShape(PaperRadii.pill))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
