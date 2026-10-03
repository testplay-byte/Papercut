package com.papercut.app.feature.viewer

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import coil.compose.AsyncImage
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScanStatus
import com.papercut.app.core.design.SegmentedPill
import com.papercut.app.core.design.StatusBadge
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * Viewer: one scan, two lenses (photo | digitized HTML), one backup slot.
 * Dark focus mode; all actions are mini-cards in one bottom strip.
 *
 * vs old app: no interdependent LaunchedEffect web — the VM exposes plain
 * actions, the screen calls them; version toggle is explicit; ONE overlay.
 */
@Composable
fun ViewerScreen(
    folderName: String,
    scanName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ViewerViewModel = appViewModel { c -> ViewerViewModel(c, context.applicationContext, folderName, scanName) }

    val item by vm.item.collectAsState()
    val status by vm.status.collectAsState()
    val showHtml by vm.showHtml.collectAsState()
    val html by vm.html.collectAsState()
    val view by vm.versionView.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val message by vm.message.collectAsState()

    var showFeedback by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    // auto-switch to the digitized view when a run completes
    LaunchedEffect(status) {
        if (status == ScanStatus.Done) vm.onStatusBecameDone()
    }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2600)
            vm.consumeMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.NightCanvas)
            .systemBarsPadding(),
    ) {
        // ---- content ----
        val current = item
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Scan not found — it may have been deleted", color = PaperColors.NightInkSecondary, fontSize = 14.sp)
            }
        } else if (showHtml && html != null) {
            HtmlPane(html = html!!, baseDir = null)
        } else {
            ZoomableImage(uri = current.imageUri)
        }

        // ---- top bar ----
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(PaperGap.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
        ) {
            val backInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pressScale(backInter)
                    .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.small))
                    .tap(backInter, onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.NightInk, modifier = Modifier.size(20.dp))
            }
            Text(
                scanName.replace("IMG_", ""),
                color = PaperColors.NightInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(status)
        }

        // ---- bottom action strip ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = PaperGap.l)
                .padding(bottom = PaperGap.l),
            verticalArrangement = Arrangement.spacedBy(PaperGap.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (current != null && current.backupUri != null && html != null) {
                SegmentedPill(
                    options = listOf(VersionView.CURRENT to "Current", VersionView.BACKUP to "Previous"),
                    selected = view,
                    onSelect = { vm.switchVersion(it) },
                    darkSurface = true,
                    modifier = Modifier.widthIn(min = 240.dp),
                )
                // resolve-the-backup chips, only meaningful while a backup exists
                Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    ActionChip("Keep new") { vm.keepCurrent() }
                    ActionChip("Restore previous", tone = PaperColors.Warning) { vm.restoreBackup() }
                }
            }

            // lens toggle + actions
            Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s), verticalAlignment = Alignment.CenterVertically) {
                ActionChip(
                    label = if (showHtml) "Photo" else "Digital",
                    icon = if (showHtml) Icons.Filled.Photo else Icons.Filled.Code,
                ) { vm.setShowHtml(!showHtml) }

                if (status == ScanStatus.Error) {
                    ActionChip("Retry", icon = Icons.Filled.Refresh, tone = PaperColors.Error) {
                        vm.reImprove(ScanMode.TEXT, feedback = null)
                    }
                } else if (status != ScanStatus.Processing && status != ScanStatus.Queued) {
                    ActionChip("Re-improve", icon = Icons.Filled.Refresh) { showFeedback = true }
                }

                if (html != null) {
                    ActionChip("Export 4K", icon = Icons.Filled.SaveAlt, enabled = !exporting) { vm.exportHighRes() }
                    ActionChip("Share", icon = Icons.Filled.Share) {
                        vm.shareImage { uri ->
                            if (uri != null) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/html"
                                    putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Share digitized page"))
                            }
                        }
                    }
                }
            }

            if (exporting) {
                LinearProgressIndicator(
                    color = PaperColors.Accent,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .clip(RoundedCornerShape(PaperRadii.pill)),
                )
            }
        }

        // single overlay while the queue works (never two, unlike old app)
        if (status == ScanStatus.Processing || status == ScanStatus.Queued) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PaperColors.ScrimDark)
                    .pointerInput(Unit) { },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PaperColors.Accent)
                    Text(
                        if (status == ScanStatus.Queued) "Waiting in queue…" else "Your AI is digitizing this page…",
                        color = PaperColors.NightInk,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = PaperGap.m),
                    )
                }
            }
        }

        message?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp)
                    .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.small))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(it, color = PaperColors.NightInk, fontSize = 13.sp) }
        }
    }

    if (showFeedback) {
        var pickedMode by remember { mutableStateOf(ScanMode.TEXT) }
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            title = { Text("Re-digitize this page") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    SegmentedPill(
                        options = listOf(ScanMode.TEXT to "Text twin", ScanMode.NOTES to "Notes"),
                        selected = pickedMode,
                        onSelect = { pickedMode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Optional: tell the AI what went wrong last time. Your note is added to the prompt.",
                        fontSize = 13.sp,
                    )
                    TextField(
                        value = feedback,
                        onValueChange = { if (it.length <= 2000) feedback = it },
                        placeholder = { Text("e.g. the table on the right was dropped") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.reImprove(pickedMode, feedback.ifBlank { null })
                    feedback = ""
                    showFeedback = false
                }) { Text("Re-run AI") }
            },
            dismissButton = {
                TextButton(onClick = { showFeedback = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tone: Color = PaperColors.NightInk,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .pressScale(interaction)
            .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.pill))
            .then(if (enabled) Modifier.tap(interaction, onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) tone else PaperColors.NightInkSecondary, modifier = Modifier.size(16.dp))
        Text(label, color = if (enabled) tone else PaperColors.NightInkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Digitized HTML pane. Reload is keyed to content identity (old app reloaded on
 * every recomposition -> flicker). MathJax CDN needs internet; timeout is the
 * WebView's own job.
 */
@Composable
private fun HtmlPane(html: String, baseDir: String?) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true // MathJax
                settings.allowFileAccess = false
                setBackgroundColor(0xFF1A1A1A.toInt())
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(baseDir, html, "text/html", "UTF-8", null)
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp),
    )
}

/**
 * Pinch-zoom + pan photo viewer. The old app's ZoomableBox gesture idea is kept
 * (single finger at scale 1 passes through) — here it's simply its own pointerInput.
 */
@Composable
private fun ZoomableImage(uri: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (newScale > 1f) offset + pan else Offset.Zero
                    scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    if (scale == 1f) offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Scan photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.scaleX = scale
                    this.scaleY = scale
                    this.translationX = offset.x
                    this.translationY = offset.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        )
    }
}

