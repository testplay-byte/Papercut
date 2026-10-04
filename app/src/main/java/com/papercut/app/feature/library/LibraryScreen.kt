package com.papercut.app.feature.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.WandMagicSparkles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.papercut.app.core.data.DocumentRepository
import com.papercut.app.core.data.model.DocumentSummary
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.ConfirmSheet
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel
import androidx.compose.ui.platform.LocalContext

/**
 * Library v2: search, folder chips, document grid (page counts + twin badges),
 * multi-select with batch digitize/delete, scan FAB. Works as the all-folders
 * home and as a scoped folder view.
 */
@Composable
fun LibraryScreen(
    scope: String?,
    onOpenDocument: (folder: String, name: String) -> Unit,
    onScanNew: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val vm: LibraryViewModel = appViewModel { c -> LibraryViewModel(c) }
    val folders by vm.folders.collectAsState()
    val docs by vm.visibleDocs.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val selected by vm.selected.collectAsState()
    val queueActive by vm.queueActive.collectAsState()

    var showCreateFolder by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<DocumentSummary?>(null) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }

    LaunchedEffectRefresh(vm, scope)

    Column(modifier = Modifier.fillMaxSize().background(PaperColors.Canvas)) {

        // ---------- header ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l)
                .padding(top = PaperGap.m, bottom = PaperGap.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
        ) {
            if (onBack != null) {
                val bi = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier.size(40.dp).pressScale(bi)
                        .clip(RoundedCornerShape(PaperRadii.small)).background(PaperColors.Tile)
                        .tap(bi, onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.Ink, modifier = Modifier.size(18.dp)) }
            }
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.papercut.app.R.drawable.ic_papercut_mark),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Text(
                scope ?: "Papercut",
                style = MaterialTheme.typography.titleLarge,
                color = PaperColors.Ink,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (queueActive > 0) {
                Text(
                    "$queueActive working",
                    color = PaperColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val si = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.size(40.dp).pressScale(si)
                    .clip(RoundedCornerShape(PaperRadii.small)).background(PaperColors.Tile)
                    .tap(si, onOpenSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, "Settings", tint = PaperColors.InkSecondary, modifier = Modifier.size(18.dp)) }
        }

        // ---------- resume unfinished capture session ----------
        val draftCount by vm.draftPages.collectAsState()
        val draftFolder by vm.draftFolder.collectAsState()
        if (draftCount > 0 && scope == null) {
            val ri = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PaperGap.l)
                    .padding(bottom = PaperGap.s)
                    .pressScale(ri, pressedScale = 0.98f)
                    .clip(RoundedCornerShape(PaperRadii.small))
                    .background(PaperColors.AccentSoft)
                    .border(1.dp, PaperColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(PaperRadii.small))
                    .tap(ri) { onScanNew() }
                    .padding(horizontal = PaperGap.m, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
            ) {
                Icon(Icons.Filled.PhotoCamera, null, tint = PaperColors.Accent, modifier = Modifier.size(20.dp))
                Text(
                    "Unsaved capture — $draftCount page(s)",
                    color = PaperColors.Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("Resume", color = PaperColors.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val di = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .pressScale(di)
                        .tap(di) { vm.discardDraft() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Delete, "Discard draft", tint = PaperColors.InkSecondary,
                        modifier = Modifier.size(18.dp))
                }
            }
        }

        // ---------- search + sort ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l)
                .padding(bottom = PaperGap.s),
            horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("Search", color = PaperColors.InkFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = PaperColors.InkFaint, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        val ci = remember { MutableInteractionSource() }
                        Icon(Icons.Filled.Close, "Clear", tint = PaperColors.InkFaint,
                            modifier = Modifier.size(18.dp).tap(ci) { vm.setQuery("") })
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PaperColors.Tile,
                    unfocusedContainerColor = PaperColors.Tile,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PaperColors.Accent,
                    focusedTextColor = PaperColors.Ink,
                    unfocusedTextColor = PaperColors.Ink,
                ),
                shape = RoundedCornerShape(PaperRadii.small),
                modifier = Modifier.weight(1f),
            )
            val soi = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.size(48.dp).pressScale(soi)
                    .clip(RoundedCornerShape(PaperRadii.small)).background(PaperColors.Tile)
                    .tap(soi) { vm.toggleSort() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Sort, "Sort: ${if (sort == DocSort.RECENT) "recent" else "name"}",
                    tint = PaperColors.InkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        // ---------- folder chips (home only) ----------
        if (scope == null) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = PaperGap.l),
                modifier = Modifier.fillMaxWidth().padding(bottom = PaperGap.s),
            ) {
                lazyItems(listOf("All") + folders.map { it.name }, key = { it }) { f ->
                    FolderChip(
                        label = f,
                        count = if (f == "All") folders.sumOf { it.docCount }
                        else folders.find { it.name == f }?.docCount ?: 0,
                        selected = false,
                        onClick = { if (f != "All") onOpenFolder(f) },
                    )
                }
                item {
                    val ni = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .pressScale(ni)
                            .clip(RoundedCornerShape(PaperRadii.pill))
                            .border(1.dp, PaperColors.TileBorder, RoundedCornerShape(PaperRadii.pill))
                            .tap(ni) { showCreateFolder = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Icon(Icons.Filled.Add, "New folder", tint = PaperColors.Accent, modifier = Modifier.size(16.dp)) }
                }
            }
        }

        // ---------- selection bar (when selecting) ----------
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l)
                    .padding(bottom = PaperGap.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
            ) {
                Text("${selected.size} selected", color = PaperColors.Accent, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, modifier = Modifier.weight(1f))
                val di = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier.pressScale(di)
                        .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                        .tap(di) { vm.digitizeSelected(scope) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.WandMagicSparkles, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("Digitize", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                val xdi = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier.pressScale(xdi)
                        .background(PaperColors.Tile, RoundedCornerShape(PaperRadii.pill))
                        .border(1.dp, PaperColors.Error.copy(alpha = 0.5f), RoundedCornerShape(PaperRadii.pill))
                        .tap(xdi) { confirmDeleteSelection = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Delete, null, tint = PaperColors.Error, modifier = Modifier.size(14.dp))
                    Text("Delete", color = PaperColors.Error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                val ci = remember { MutableInteractionSource() }
                Icon(Icons.Filled.Close, "Cancel selection", tint = PaperColors.InkSecondary,
                    modifier = Modifier.size(20.dp).tap(ci) { vm.clearSelection() })
            }
        }

        // ---------- docs grid ----------
        if (docs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 90.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.papercut.app.R.drawable.ic_papercut_mark),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        if (query.isNotBlank()) "No matches" else "No documents yet",
                        color = PaperColors.InkSecondary, fontSize = 14.sp,
                        modifier = Modifier.padding(top = PaperGap.m),
                    )
                    if (query.isBlank()) {
                        val ctaI = remember { MutableInteractionSource() }
                        Text(
                            "Scan your first page",
                            color = PaperColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp).pressScale(ctaI).tap(ctaI, onScanNew).padding(8.dp),
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = PaperGap.l, end = PaperGap.l, top = PaperGap.s, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
                verticalArrangement = Arrangement.spacedBy(PaperGap.s),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(docs, key = { "${it.folder}/${it.name}" }) { doc ->
                    val isSelected = (doc.folder to doc.name) in selected
                    DocTile(
                        doc = doc,
                        isSelected = isSelected,
                        onClick = {
                            if (selected.isNotEmpty()) vm.toggleSelect(doc.folder, doc.name)
                            else onOpenDocument(doc.folder, doc.name)
                        },
                        // first long-press ENTERS selection (headline feature was unreachable before)
                        onLongPress = { vm.toggleSelect(doc.folder, doc.name) },
                        onManage = { manageTarget = doc },
                    )
                }
            }
        }
    }

    // ---------- scan FAB ----------
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        val fi = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .padding(end = PaperGap.l, bottom = 24.dp)
                .size(58.dp)
                .pressScale(fi)
                .clip(CircleShape)
                .background(PaperColors.Accent)
                .tap(fi, onScanNew),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "Scan", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    // ---------- dialogs ----------
    if (showCreateFolder) {
        NameDialog("New folder", "Folder name", onDismiss = { showCreateFolder = false }) {
            vm.createFolder(it) { showCreateFolder = false }
        }
    }

    manageTarget?.let { doc ->
        var rename by remember { mutableStateOf(false) }
        var confirmDelete by remember(doc.name) { mutableStateOf(false) }
        when {
            rename -> NameDialog("Rename", "New name", initial = doc.name,
                onDismiss = { rename = false; manageTarget = null }) {
                vm.renameDocument(doc, it); rename = false; manageTarget = null
            }
            confirmDelete -> ConfirmSheet(
                title = "Delete “${doc.name}”?",
                message = "All ${doc.pageCount} pages and their digital twins are removed from disk.",
                confirmLabel = "Delete",
                onConfirm = { vm.deleteDocument(doc); manageTarget = null },
                onDismiss = { manageTarget = null },
            )
            else -> AlertDialog(
                onDismissRequest = { manageTarget = null },
                containerColor = PaperColors.Tile,
                title = { Text(doc.name) },
                text = { Text("${doc.pageCount} pages · ${doc.digitizedPages} digitized", color = PaperColors.InkSecondary) },
                confirmButton = {
                    if (!doc.isLegacy) TextButton(onClick = { rename = true }) { Text("Rename") }
                    else TextButton(onClick = { manageTarget = null }) { Text("Close") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete", color = PaperColors.Error)
                        }
                    }
                },
            )
        }
    }

    if (confirmDeleteSelection) {
        val n = selected.size
        ConfirmSheet(
            title = if (n == 1) "Delete 1 document?" else "Delete $n documents?",
            message = "Pages and digital twins are removed from disk.",
            confirmLabel = "Delete all",
            onConfirm = { vm.deleteSelected(); confirmDeleteSelection = false },
            onDismiss = { confirmDeleteSelection = false },
        )
    }
}

/** Load data on (re)enter; VM survives scope switch via key in appViewModel… */
@Composable
private fun LaunchedEffectRefresh(vm: LibraryViewModel, scope: String?) {
    androidx.compose.runtime.LaunchedEffect(scope) { vm.load(scope) }
}

@Composable
private fun FolderChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .pressScale(inter, pressedScale = 0.95f)
            .background(if (selected) PaperColors.AccentSoft else PaperColors.Tile,
                RoundedCornerShape(PaperRadii.pill))
            .border(1.dp, if (selected) PaperColors.Accent.copy(alpha = 0.6f) else PaperColors.TileBorder,
                RoundedCornerShape(PaperRadii.pill))
            .tap(inter, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) PaperColors.Accent else PaperColors.Ink)
        Text("$count", color = PaperColors.InkSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DocTile(
    doc: DocumentSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onManage: () -> Unit,
) {
    val inter = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .aspectRatio(0.86f)
            .fillMaxWidth()
            .pressScale(inter, pressedScale = 0.97f)
            .clip(RoundedCornerShape(PaperRadii.tile))
            .background(if (isSelected) PaperColors.AccentSoft else PaperColors.Tile)
            .border(
                1.dp,
                if (isSelected) PaperColors.Accent else PaperColors.TileBorder,
                RoundedCornerShape(PaperRadii.tile),
            )
            .combinedClickable(
                interactionSource = inter,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        // cover
        Box(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (doc.coverUri != null) {
                AsyncImage(
                    model = doc.coverUri,
                    contentDescription = doc.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Text(
                    doc.name.take(1).uppercase(),
                    fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    color = PaperColors.InkFaint,
                )
            }
        }

        // bottom label strip
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(PaperColors.Tile.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(doc.name, style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink,
                maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${doc.pageCount}p", color = PaperColors.InkSecondary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"))
                if (doc.digitizedPages > 0) {
                    Text("${doc.digitizedPages}✓", fontSize = 11.sp, color = PaperColors.Success,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // selection check overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(24.dp)
                    .background(PaperColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        } else {
            // manage button (rename/delete) — long-press is reserved for selection
            val mi = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .tap(mi, onManage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MoreVert, "Manage", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Reusable single-field dialog. */
@Composable
fun NameDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaperColors.Tile,
        title = { Text(title) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PaperColors.TileSunken,
                    unfocusedContainerColor = PaperColors.TileSunken,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PaperColors.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
