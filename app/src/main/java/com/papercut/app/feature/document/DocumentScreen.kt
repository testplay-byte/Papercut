package com.papercut.app.feature.document

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import com.papercut.app.core.data.PagePipeline
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.PageView
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScanStatus
import com.papercut.app.core.design.StatusBadge
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * Document hub: the rendered page (crop+rotate+filter applied live), a
 * filmstrip, and everything about this page — digitize, view HTML, versions,
 * page ops, export (PDF / 4K / share). Dark review chrome, one action row.
 */
@Composable
fun DocumentScreen(
    folderName: String,
    docName: String,
    onBack: () -> Unit,
    onAddPages: () -> Unit,
) {
    val context = LocalContext.current
    val vm: DocumentViewModel = appViewModel { c -> DocumentViewModel(c, folderName, docName) }
    val ui by vm.ui.collectAsState()
    val statusMap by vm.statuses.collectAsState()
    val busy by vm.busy.collectAsState()

    var selected by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(ViewMode.PAGE) }
    var showExport by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var confirmDeletePage by remember { mutableStateOf(false) }

    // hoisted: the dialogs below (outside the when) need these
    val pages = (ui as? DocumentViewModel.Ui.Ready)?.pages ?: emptyList()
    val idx = selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val page = pages.getOrNull(idx)
    val status = page?.let { vm.statusOf(it) } ?: ScanStatus.Plain
    val statusMessage = page?.let {
        statusMap[com.papercut.app.core.domain.ProcessingQueue.statusKey(
            folderName, docName, it.spec.index,
        )]?.message
    }

    // twin-ready moment: flip to the digital view once per completed digitize.
    // Page indexes shift on delete/reorder -> prune so shifted pages can flip too.
    val flippedFor = remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(pages.size, selected) { flippedFor.value = flippedFor.value.intersect(pages.indices.toSet()) }
    LaunchedEffect(statusMap, page?.spec?.index) {
        val p = page ?: return@LaunchedEffect
        if (status == ScanStatus.Done && p.spec.index !in flippedFor.value) {
            flippedFor.value = flippedFor.value + p.spec.index
            vm.showHtml(p, backup = false)
            mode = ViewMode.HTML
        }
    }

    when (ui) {
        DocumentViewModel.Ui.Loading -> Box(Modifier.fillMaxSize().background(PaperColors.NightCanvas),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PaperColors.Accent)
        }
        DocumentViewModel.Ui.NotFound -> CenterNote("This document no longer exists.", onBack)
        is DocumentViewModel.Ui.Ready -> {
            if (page == null) { CenterNote("Document is empty.", onBack); return }

            Column(modifier = Modifier.fillMaxSize().background(PaperColors.NightCanvas)) {
                // top bar
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = PaperGap.m, vertical = PaperGap.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
                ) {
                    val backInter = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier.size(40.dp).pressScale(backInter)
                            .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.small))
                            .tap(backInter, onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.NightInk, modifier = Modifier.size(18.dp)) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(docName, color = PaperColors.NightInk, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, fontSize = 16.sp)
                        Text("${idx + 1} / ${pages.size}",
                            color = PaperColors.NightInkSecondary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp, fontFeatureSettings = "tnum",
                            ))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        StatusBadge(status)
                        if (status == ScanStatus.Error && statusMessage != null) {
                            Text(statusMessage, color = PaperColors.Error, fontSize = 10.sp, maxLines = 2,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    val addInter = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier.size(40.dp).pressScale(addInter)
                            .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.small))
                            .tap(addInter, onAddPages),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, "Add pages", tint = PaperColors.Accent, modifier = Modifier.size(18.dp))
                    }
                }

                // main stage
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (mode) {
                        ViewMode.PAGE -> RenderedPage(vm = vm, page = page)
                        ViewMode.HTML -> HtmlStage(
                            html = vm.htmlContent.collectAsState().value,
                            backup = vm.showingBackup,
                        )
                    }

                    // single overlay while working — with a real Cancel
                    if (status == ScanStatus.Processing || status == ScanStatus.Queued) {
                        WorkingOverlay(
                            queued = status == ScanStatus.Queued,
                            onCancel = { vm.cancelDigitize(page); mode = ViewMode.PAGE },
                        )
                    }
                    if (busy != null) {
                        Box(Modifier.fillMaxSize().background(PaperColors.ScrimDark), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = PaperColors.Accent)
                                Text(busy ?: "", color = PaperColors.NightInk, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = PaperGap.m))
                            }
                        }
                    }
                }

                // filmstrip
                if (pages.size > 1) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(idx) {
                        if (idx in pages.indices) listState.animateScrollToItem(idx)
                    }
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = PaperGap.l, vertical = PaperGap.s),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        items(pages.size) { i ->
                            PageThumb(vm = vm, page = pages[i], status = vm.statusOf(pages[i]),
                                selected = i == idx, onClick = { selected = i; mode = ViewMode.PAGE })
                        }
                    }
                }

                // action bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.m)
                        .padding(bottom = PaperGap.m),
                    horizontalArrangement = Arrangement.spacedBy(PaperGap.s, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionBtn(
                        label = if (mode == ViewMode.HTML) "Page" else "Twin",
                        icon = if (mode == ViewMode.HTML) Icons.Filled.Photo else Icons.Filled.Code,
                        active = mode == ViewMode.HTML,
                    ) {
                        if (mode == ViewMode.HTML) mode = ViewMode.PAGE
                        else { mode = ViewMode.HTML; vm.showHtml(page, backup = false) }
                    }
                    ActionBtn(
                        label = "Digitize",
                        icon = Icons.Filled.AutoAwesome,
                        enabled = status != ScanStatus.Processing && status != ScanStatus.Queued,
                    ) {
                        vm.digitize(page, page.spec.aiMode, null)
                        mode = ViewMode.PAGE
                    }
                    ActionBtn(label = "Re-run", icon = Icons.Filled.Refresh,
                        enabled = page.htmlUri != null || status == ScanStatus.Error) {
                        showFeedback = true
                    }

                    ActionBtn(label = "Export", icon = Icons.Filled.SaveAlt) { showExport = true }
                    ActionBtn(label = "Share", icon = Icons.Filled.Share, enabled = page.imageUri.isNotBlank()) {
                        vm.shareOriginal(page) { uriStr ->
                            if (uriStr != null) {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uriStr))
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share page")) }
                            }
                        }
                    }
                }

                // page ops + version strip (compact, secondary)
                val hasBackup = page.backupUri != null
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l)
                        .padding(bottom = PaperGap.m),
                    horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { vm.movePage(pages, page.spec.index, -1) },
                        enabled = idx > 0) { Text("◀ Move", color = PaperColors.InkSecondary, fontSize = 12.sp) }
                    TextButton(onClick = { vm.movePage(pages, page.spec.index, 1) },
                        enabled = idx < pages.lastIndex) { Text("Move ▶", color = PaperColors.InkSecondary, fontSize = 12.sp) }
                    TextButton(onClick = { vm.rotatePage(page) }) {
                        Text("Rotate", color = PaperColors.InkSecondary, fontSize = 12.sp)
                    }
                    if (hasBackup) {
                        TextButton(onClick = { vm.showHtml(page, backup = true); mode = ViewMode.HTML }) {
                            Text("Old twin", color = PaperColors.Warning, fontSize = 12.sp)
                        }
                    }
                    Box(modifier = Modifier.weight(1f))
                    val delInter = remember { MutableInteractionSource() }
                    if (pages.size > 1) {
                        Icon(
                            Icons.Filled.Delete, "Delete page",
                            tint = PaperColors.Error,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(PaperColors.NightTile)
                                .padding(6.dp)
                                .pressScale(delInter)
                                .tap(delInter) { confirmDeletePage = true },
                        )
                    }
                }

                if (hasBackup && page.htmlUri != null && mode == ViewMode.HTML && vm.showingBackup) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l, vertical = PaperGap.s),
                        horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
                    ) {
                        ActionBtn(label = "Keep new", icon = Icons.AutoMirrored.Filled.List) { vm.discardBackup(page) }
                        ActionBtn(label = "Restore", icon = Icons.Filled.Refresh) { vm.restoreBackup(page) }
                    }
                }
            }
        }
    }

    // feedback dialog (re-run with correction)
    if (showFeedback) {
        var pickMode by remember { mutableStateOf(page?.spec?.aiMode ?: ScanMode.TEXT) }
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            containerColor = PaperColors.Tile,
            title = { Text("Re-digitize page ${idx + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    com.papercut.app.core.design.SegmentedPill(
                        options = listOf(ScanMode.TEXT to "Text twin", ScanMode.NOTES to "Notes"),
                        selected = pickMode, onSelect = { pickMode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.TextField(
                        value = feedback,
                        onValueChange = { if (it.length <= 2000) feedback = it },
                        placeholder = { Text("What to fix (optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = status != ScanStatus.Queued && status != ScanStatus.Processing,
                    onClick = {
                        page?.let { vm.digitize(it, pickMode, feedback.ifBlank { null }) }
                        feedback = ""; showFeedback = false; mode = ViewMode.PAGE
                    },
                ) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showFeedback = false }) { Text("Cancel") } },
        )
    }

    // export sheet
    if (showExport) {
        ExportSheet(vm = vm, page = page, onDismiss = { showExport = false })
    }

    // delete-page confirm
    if (confirmDeletePage) {
        val victim = page
        com.papercut.app.core.design.ConfirmSheet(
            title = "Delete page ${victim?.spec?.index ?: ""}?",
            message = "The photo and its digital twin are removed from disk.",
            confirmLabel = "Delete",
            onConfirm = {
                if (victim != null) {
                    vm.deletePage(victim)
                    if (selected >= pages.size - 1) selected = (pages.size - 2).coerceAtLeast(0)
                }
                confirmDeletePage = false
            },
            onDismiss = { confirmDeletePage = false },
        )
    }
}

private enum class ViewMode { PAGE, HTML }

/** Live-rendered edited page with pinch-zoom + double-tap. */
@Composable
private fun RenderedPage(vm: DocumentViewModel, page: PageView) {
    var bitmap by remember(page.spec.index, page.spec.quad, page.spec.rotation, page.spec.filter) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(page) { bitmap = vm.renderPage(page) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page.spec.index) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (ns > 1f) offset + pan else Offset.Zero
                    scale = ns
                }
            }
            .pointerInput(page.spec.index) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    if (scale == 1f) offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = PaperColors.Accent)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

@Composable
private fun HtmlStage(html: String?, backup: Boolean) {
    if (html == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No digital twin yet.", color = PaperColors.NightInkSecondary, fontSize = 14.sp)
                Text("Tap Digitize to create one.", color = PaperColors.Accent, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    // Twins are authored for a ~1080px page; without a wide viewport the text
    // renders at ~7sp on a phone — the headline feature looked unreadable.
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    setBackgroundColor(0xFF0E0E11.toInt())
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    val withViewport = html.replaceFirst(
                        Regex("<head>", RegexOption.IGNORE_CASE),
                        "<head><meta name="viewport" content="width=1080, initial-scale=1">",
                    )
                    view.loadDataWithBaseURL(null, withViewport, "text/html", "UTF-8", null)
                }
                view.setZoomControlsShown(true)
                if (zoomed) view.zoomBy(1.6f) else view.resetZoom()
            },
            modifier = Modifier.fillMaxSize(),
        )
        val fitInter = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PaperGap.s)
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperColors.NightTile)
                .border(1.dp, PaperColors.TileBorder, CircleShape)
                .pressScale(fitInter)
                .tap(fitInter) { zoomed = !zoomed },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (zoomed) "1:1" else "Fit", color = PaperColors.NightInkSecondary, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PageThumb(vm: DocumentViewModel, page: PageView, status: ScanStatus, selected: Boolean, onClick: () -> Unit) {
    var thumb by remember(page.spec.index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(page) { thumb = vm.thumbnail(page) }
    val inter = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(56.dp)
            .pressScale(inter, pressedScale = 0.94f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PaperColors.Accent else PaperColors.NightTile)
            .padding(if (selected) 2.dp else 0.dp)
            .tap(inter, onClick),
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(bitmap = t.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
        }
        // page number badge — always visible, bottom-left
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text("${page.spec.index}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
        }
        if (status == ScanStatus.Processing || status == ScanStatus.Queued) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = PaperColors.Accent, modifier = Modifier.size(16.dp))
            }
        } else if (status == ScanStatus.Done) {
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(7.dp).background(PaperColors.Success, CircleShape))
        }
    }
}

@Composable
private fun WorkingOverlay(queued: Boolean, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(PaperColors.ScrimDark), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = PaperColors.Accent)
            Text(if (queued) "Waiting…" else "Digitizing…",
                color = PaperColors.NightInk, fontSize = 13.sp, modifier = Modifier.padding(top = PaperGap.m))
            val cancelInter = remember { MutableInteractionSource() }
            Text(
                "Cancel",
                color = PaperColors.NightInkSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = PaperGap.l)
                    .pressScale(cancelInter)
                    .tap(cancelInter, onCancel)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun ActionBtn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val inter = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .pressScale(inter, pressedScale = 0.94f)
            .background(
                if (active) PaperColors.Accent else PaperColors.NightTile,
                RoundedCornerShape(PaperRadii.pill),
            )
            .then(if (enabled) Modifier.tap(inter, onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(icon, null,
            tint = when {
                !enabled -> PaperColors.InkFaint
                active -> PaperColors.Canvas
                else -> PaperColors.NightInk
            },
            modifier = Modifier.size(15.dp))
        Text(label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> PaperColors.InkFaint
                active -> PaperColors.Canvas
                else -> PaperColors.NightInk
            })
    }
}

@Composable
private fun CenterNote(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(PaperColors.NightCanvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = PaperColors.NightInkSecondary, fontSize = 14.sp)
            val inter = remember { MutableInteractionSource() }
            Text("Back", color = PaperColors.Accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = PaperGap.m).pressScale(inter).tap(inter, onBack).padding(8.dp))
        }
    }
}

/** Export sheet: PDF (all pages) / 4K twin image (current) / share current twin HTML. */
@Composable
private fun ExportSheet(
    vm: DocumentViewModel,
    page: PageView?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaperColors.Tile,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                SheetRow("PDF — all pages", "Full document, corrected pages") {
                    vm.exportPdf(share = false) { uri ->
                        if (uri != null) {
                            runCatching {
                                context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, uri,
                                ).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        }
                    }
                    onDismiss()
                }
                SheetRow("PDF — share", "Send the document anywhere") {
                    vm.exportPdf(share = true) { uri ->
                        if (uri != null) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share PDF")) }
                        }
                    }
                    onDismiss()
                }
                if (page?.htmlUri != null) {
                    SheetRow("4K image — this page", "Digitized twin rendered to PNG") {
                        vm.exportHtmlAsImage(page)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SheetRow(title: String, subtitle: String, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(inter, pressedScale = 0.98f)
            .background(PaperColors.TileSunken, RoundedCornerShape(PaperRadii.small))
            .tap(inter, onClick)
            .padding(PaperGap.m),
    ) {
        Text(title, color = PaperColors.Ink, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = PaperColors.InkSecondary, fontSize = 12.sp)
    }
}
