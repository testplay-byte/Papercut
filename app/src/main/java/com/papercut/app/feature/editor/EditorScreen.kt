package com.papercut.app.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.core.data.PagePipeline
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.PageSpec
import com.papercut.app.core.data.model.Quad
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel

/**
 * Page crop & filter editor for ONE draft page.
 *
 * Crop mode: the rotated page is shown with a draggable 4-corner quad; the
 * area OUTSIDE the quad is dimmed so the cut is legible. Handles are drawn in
 * the same Canvas as the image, so they align to pixels exactly.
 * Preview mode: the fully-corrected render (warp + rotation + filter).
 *
 * Edits buffer in the ViewModel and commit to the app-scoped DraftStore on
 * Apply (back without applying discards them).
 */
@Composable
fun EditorScreen(draftIndex: Int, onBack: () -> Unit) {
    val vm: EditorViewModel = appViewModel { c -> EditorViewModel(c, draftIndex) }
    val state by vm.state.collectAsState()

    if (state == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val s = state!!
    var previewMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(PaperColors.NightCanvas),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.m, vertical = PaperGap.m),
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

            Text("Page ${draftIndex + 1}", color = PaperColors.NightInk, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), fontSize = 16.sp)

            val prevInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.pressScale(prevInter)
                    .background(if (previewMode) PaperColors.Accent else PaperColors.NightTile,
                        RoundedCornerShape(PaperRadii.pill))
                    .tap(prevInter) { previewMode = !previewMode }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(if (previewMode) "Original" else "Preview",
                    color = if (previewMode) Color.White else PaperColors.NightInkSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            val applyInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.size(40.dp).pressScale(applyInter)
                    .background(PaperColors.Accent, CircleShape)
                    .tap(applyInter) { vm.applyAndClose(); onBack() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "Apply", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        Text(
            "Drag the corners · ✓ to apply",
            color = PaperColors.NightInkSecondary, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = PaperGap.m),
            contentAlignment = Alignment.Center,
        ) {
            if (previewMode) {
                val rendered = remember(s.quad, s.rotation, s.filter, s.bitmap) {
                    PagePipeline.render(s.bitmap, PageSpec(1, "", s.quad, s.rotation, s.filter))
                }
                DisposableEffect(rendered) { onDispose { if (rendered !== s.bitmap) rendered.recycle() } }
                Image(bitmap = rendered.asImageBitmap(), contentDescription = "corrected",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CropStage(
                    bitmap = s.bitmap,
                    rotation = s.rotation,
                    filter = s.filter,
                    quad = s.quad ?: Quad.FULL,
                    onQuadChange = { vm.setQuad(it) },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaperGap.l)
                .padding(top = PaperGap.s, bottom = PaperGap.l),
            verticalArrangement = Arrangement.spacedBy(PaperGap.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                val rotInter = remember { MutableInteractionSource() }
                val fullInter = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier.size(44.dp).pressScale(rotInter)
                        .background(PaperColors.NightTile, CircleShape)
                        .tap(rotInter) { vm.rotate() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.RotateRight, "Rotate", tint = PaperColors.NightInk, modifier = Modifier.size(20.dp)) }
                Box(
                    modifier = Modifier.size(44.dp).pressScale(fullInter)
                        .background(if (s.quad != null) PaperColors.Accent else PaperColors.NightTile, CircleShape)
                        .tap(fullInter) { vm.clearQuad() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CropFree, "Full page",
                        tint = if (s.quad != null) Color.White else PaperColors.NightInk, modifier = Modifier.size(20.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(PageFilter.MAGIC, "Magic", s.filter, Icons.Filled.AutoAwesome) { vm.setFilter(it) }
                FilterChip(PageFilter.ORIGINAL, "Original", s.filter) { vm.setFilter(it) }
                FilterChip(PageFilter.GRAYSCALE, "Gray", s.filter) { vm.setFilter(it) }
                FilterChip(PageFilter.BLACK_WHITE, "B&W", s.filter) { vm.setFilter(it) }
            }
        }
    }
}

/** The photo + draggable quad, aligned in one Canvas. */
@Composable
private fun CropStage(
    bitmap: android.graphics.Bitmap,
    rotation: Int,
    filter: PageFilter,
    quad: Quad,
    onQuadChange: (Quad) -> Unit,
) {
    // show the rotated raw image (no warp) so corners track what the user sees
    val base = remember(bitmap, rotation) {
        if (rotation == 0) bitmap
        else {
            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
    }
    DisposableEffect(base) { onDispose { if (base !== bitmap) base.recycle() } }

    var dragIndex by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val constraintsPx = with(LocalDensity.current) {
            Size(maxWidth.toPx(), maxHeight.toPx())
        }
        val imgAspect = base.width.toFloat() / base.height
        val boxAspect = constraintsPx.width / constraintsPx.height
        val drawW: Float; val drawH: Float
        if (imgAspect > boxAspect) { drawW = constraintsPx.width; drawH = constraintsPx.width / imgAspect }
        else { drawH = constraintsPx.height; drawW = constraintsPx.height * imgAspect }
        val offX = (constraintsPx.width - drawW) / 2f
        val offY = (constraintsPx.height - drawH) / 2f

        fun n2p(nx: Float, ny: Float) = Offset(offX + nx * drawW, offY + ny * drawH)

        val bmpImage = remember(base) { base.asImageBitmap() }
        // latest quad WITHOUT restarting the gesture (quad changes every drag frame)
        val currentQuad by rememberUpdatedState(quad)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(base, drawW, drawH, offX, offY) {
                    detectDragGestures(
                        onDragStart = { start ->
                            dragIndex = currentQuad.corners()
                                .mapIndexed { i, c -> i to n2p(c.first, c.second) }
                                .minByOrNull { (_, p) -> (p - start).getDistance() }
                                ?.takeIf { (_, p) -> (p - start).getDistance() < 120f }
                                ?.first ?: -1
                        },
                        onDrag = { change, _ ->
                            val idx = dragIndex
                            if (idx < 0) return@detectDragGestures
                            val nx = ((change.position.x - offX) / drawW).coerceIn(0f, 1f)
                            val ny = ((change.position.y - offY) / drawH).coerceIn(0f, 1f)
                            onQuadChange(currentQuad.withCorner(idx, nx, ny))
                        },
                        onDragEnd = { dragIndex = -1 },
                        onDragCancel = { dragIndex = -1 },
                    )
                },
        ) {
            drawImage(
                image = bmpImage,
                dstOffset = androidx.compose.ui.unit.IntOffset(offX.toInt(), offY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
            )

            val corners = quad.corners().map { n2p(it.first, it.second) }

            // dim everything OUTSIDE the crop quad: one even-odd path = frame + quad
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                val inner = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    lineTo(corners[1].x, corners[1].y)
                    lineTo(corners[2].x, corners[2].y)
                    lineTo(corners[3].x, corners[3].y)
                    close()
                }
                addPath(inner)
            }
            drawPath(outside, Color(0x99000000))

            val edge = PaperColors.Accent
            val quadPath = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                lineTo(corners[1].x, corners[1].y)
                lineTo(corners[2].x, corners[2].y)
                lineTo(corners[3].x, corners[3].y)
                close()
            }
            drawPath(quadPath, edge, style = Stroke(width = 4f, cap = StrokeCap.Round))

            corners.forEachIndexed { i, c ->
                drawCircle(if (i == dragIndex) edge else Color.White, radius = 20f, center = c)
                if (i != dragIndex) drawCircle(edge, radius = 13f, center = c)
            }
        }
    }
}

@Composable
private fun FilterChip(
    value: PageFilter,
    label: String,
    selected: PageFilter,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSelect: (PageFilter) -> Unit,
) {
    val active = value == selected
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .background(if (active) PaperColors.NightInk else PaperColors.NightTile, RoundedCornerShape(PaperRadii.pill))
            .tap(interaction) { onSelect(value) }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) PaperColors.NightCanvas else PaperColors.Accent, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) PaperColors.NightCanvas else PaperColors.NightInkSecondary)
    }
}
