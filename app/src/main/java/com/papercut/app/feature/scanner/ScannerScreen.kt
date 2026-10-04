package com.papercut.app.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.SegmentedPill
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.core.domain.OrientationTracker
import com.papercut.app.di.appViewModel
import java.io.File
import java.util.concurrent.Executors

/**
 * Scanner — multi-page capture session (dark focus chrome).
 * Camera stays open; shots pile into the bottom draft rail: tap a thumb to
 * crop/edit, long-press to drop it. Done opens the save sheet, which either
 * creates a new document or appends pages to an existing one.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    folderName: String,
    appendToDoc: String?,
    onBack: () -> Unit,
    onEditPage: (Int) -> Unit,
    onSaved: (String, String) -> Unit,
    onDraftKept: (Int) -> Unit,
) {
    val vm: ScannerViewModel = appViewModel { c -> ScannerViewModel(c, folderName, appendToDoc) }
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    var showSaveSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.NightCanvas),
    ) {
        if (!cameraPermission.status.isGranted) {
            PermissionNudge(onAllow = { cameraPermission.launchPermissionRequest() })
            return@Box
        }

        val flash by vm.flash.collectAsState()
        val mode by vm.mode.collectAsState()
        val capturing by vm.capturing.collectAsState()
        val saving by vm.saving.collectAsState()
        val pages by vm.pages.collectAsState()
        val draftName by vm.suggestedName.collectAsState()

        // ---- orientation: continuous smoothing, hysteresis quadrant -> EXIF ----
        val tracker = remember { OrientationTracker() }
        var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
        DisposableEffect(imageCapture) {
            val listener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    tracker.onRawAngle(deg.toFloat())
                    imageCapture?.targetRotation = tracker.targetRotation
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }

        // ---- camera bound once for the screen's lifetime ----
        val previewView = remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        var boundProvider: ProcessCameraProvider? = null
        DisposableEffect(lifecycleOwner) {
            // never .get() on main: cold camera start blocks up to seconds (ANR)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = try { future.get() } catch (_: Exception) { return@addListener }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                boundProvider = provider
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
            onDispose {
                boundProvider?.unbindAll()
                imageCapture = null
            }
        }

        LaunchedEffect(flash, imageCapture) {
            imageCapture?.flashMode = when (flash) {
                Flash.OFF -> ImageCapture.FLASH_MODE_OFF
                Flash.ON -> ImageCapture.FLASH_MODE_ON
                Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
            }
        }

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ViewfinderOverlay()

        // ---- top row: close | mode pill | flash ----
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = PaperGap.m, vertical = PaperGap.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
        ) {
            val closeInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(closeInter)
                    .background(PaperColors.NightTile, CircleShape)
                    .tap(closeInter) {
                        if (pages.isNotEmpty()) onDraftKept(pages.size)
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "Close", tint = PaperColors.NightInk, modifier = Modifier.size(20.dp))
            }
            SegmentedPill(
                options = listOf(ScanMode.TEXT to "Text", ScanMode.NOTES to "Notes"),
                selected = mode,
                onSelect = { vm.setMode(it) },
                darkSurface = true,
                modifier = Modifier.weight(1f),
            )
            FlashButton(flash) {
                vm.setFlash(when (flash) {
                    Flash.OFF -> Flash.ON
                    Flash.ON -> Flash.AUTO
                    Flash.AUTO -> Flash.OFF
                })
            }
        }

        // ---- shutter ----
        val shutterInter = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (pages.isEmpty()) 36.dp else 138.dp)
                .size(74.dp)
                .pressScale(shutterInter, pressedScale = if (capturing) 1f else 0.93f)
                .background(PaperColors.NightTile, CircleShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(if (capturing) PaperColors.InkFaint else Color.White, CircleShape)
                    .tap(shutterInter) {
                        if (!capturing && !saving) {
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            vm.beginCapture()
                            takePicture(context, imageCapture, vm)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = PaperColors.Accent,
                        modifier = Modifier.size(22.dp))
                }
            }
        }

        // ---- draft rail ----
        if (pages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(PaperColors.NightCanvas.copy(alpha = 0.94f))
                    .padding(horizontal = PaperGap.m, vertical = PaperGap.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PaperGap.s),
            ) {
                Text(
                    "${pages.size}",
                    color = PaperColors.Accent,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFeatureSettings = "tnum",
                    ),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(pages.size) { i ->
                        val page = pages[i]
                        DraftThumb(
                            bitmap = page.bitmap,
                            edited = page.quad != null || page.rotation != 0 || page.filter != PageFilter.MAGIC,
                            onTap = { onEditPage(i) },
                            onDelete = { vm.removePage(i) },
                        )
                    }
                }
                val doneInter = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .width(84.dp)
                        .pressScale(doneInter)
                        .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                        .tap(doneInter) { showSaveSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (appendToDoc != null) "Add" else "Done",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
            }
        }

        // ---- save sheet ----
        if (showSaveSheet) {
            var docName by remember { mutableStateOf(draftName) }
            AlertDialog(
                onDismissRequest = { if (!saving) showSaveSheet = false },
                containerColor = PaperColors.Tile,
                title = {
                    Text(
                        if (appendToDoc != null) "Add ${pages.size} page(s) to “$appendToDoc”"
                        else "Save document",
                    )
                },
                text = {
                    if (appendToDoc == null) {
                        TextField(
                            value = docName,
                            onValueChange = { docName = it },
                            singleLine = true,
                            label = { Text("Document name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Pages are appended to the end, in order.")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.setName(docName)
                            vm.saveDraft { savedFolder, savedName, ok ->
                                if (ok && savedName != null) onSaved(savedFolder ?: folderName, savedName)
                                else showSaveSheet = false
                            }
                        },
                        enabled = !saving && pages.isNotEmpty(),
                    ) { Text(if (saving) "Saving…" else if (appendToDoc != null) "Add pages" else "Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveSheet = false }, enabled = !saving) { Text("Keep scanning") }
                },
            )
        }
    }
}

@Composable
private fun FlashButton(flash: Flash, onCycle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val icon = when (flash) {
        Flash.OFF -> Icons.Filled.FlashOff
        Flash.ON -> Icons.Filled.FlashOn
        Flash.AUTO -> Icons.Filled.FlashAuto
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressScale(interaction)
            .background(if (flash == Flash.OFF) PaperColors.NightTile else PaperColors.Accent, CircleShape)
            .tap(interaction, onCycle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "Flash $flash",
            tint = if (flash == Flash.OFF) PaperColors.NightInk else Color.White,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DraftThumb(bitmap: Bitmap, edited: Boolean, onTap: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(52.dp)
            .pressScale(interaction, pressedScale = 0.92f)
            .clip(RoundedCornerShape(12.dp))
            .background(PaperColors.NightTile)
            .combinedTap(interaction, onTap, onDelete),
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (edited) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(8.dp)
                    .background(PaperColors.Accent, CircleShape),
            )
        }
    }
}

/** tap = edit, long-press = delete draft page. */
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionNudge(onAllow: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PaperGap.m, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize(),
    ) {
        Text("Papercut needs the camera.", color = PaperColors.NightInk, fontSize = 16.sp)
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .pressScale(interaction)
                .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                .tap(interaction, onAllow)
                .padding(horizontal = 26.dp, vertical = 12.dp),
        ) {
            Text("Allow camera", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One capture worker for the whole screen lifetime (no per-shutter thread leak). */
private val captureExecutor by lazy { Executors.newSingleThreadExecutor() }

/**
 * Capture to a temp file. CameraX encodes the hysteresis-stable targetRotation
 * as EXIF; we decode downsampled and BAKE the EXIF rotation into pixels
 * (BitmapFactory ignores EXIF; the draft keeps upright bitmaps only).
 */
private fun takePicture(context: Context, imageCapture: ImageCapture?, vm: ScannerViewModel) {
    val capture = imageCapture ?: run { vm.captureFailed("Camera not ready yet"); return }
    val tmp = File(context.cacheDir, "papercut_capture.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(tmp).build()
    capture.takePicture(
        options,
        captureExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 2400)
                }
                var bmp = BitmapFactory.decodeFile(tmp.path, opts)
                if (bmp != null) bmp = bakeExifRotation(bmp, tmp)
                tmp.delete()
                if (bmp == null) vm.captureFailed("Could not read the capture")
                else vm.onImageCaptured(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                tmp.delete()
                vm.captureFailed("Capture failed (${exception.imageCaptureError})")
            }
        },
    )
}

private fun bakeExifRotation(bmp: Bitmap, file: File): Bitmap {
    val rotation = try {
        when (ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
    if (rotation == 0f) return bmp
    val m = android.graphics.Matrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (rotated !== bmp) bmp.recycle()
    return rotated
}

private fun sampleSizeFor(w: Int, h: Int, maxSide: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** Rule-of-thirds grid + corner brackets — pure Canvas, night chrome. */
@Composable
private fun ViewfinderOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val grid = Color.White.copy(alpha = 0.18f)
        for (i in 1..2) {
            drawLine(grid, Offset(w * i / 3f, h * 0.16f), Offset(w * i / 3f, h * 0.84f), 1.5f)
            drawLine(grid, Offset(w * 0.08f, h * i / 3f), Offset(w * 0.92f, h * i / 3f), 1.5f)
        }
        val bracket = Color.White.copy(alpha = 0.8f)
        val len = 44f
        val insetX = w * 0.08f
        val insetY = h * 0.17f
        listOf(
            Triple(insetX, insetY, 1 to 1),
            Triple(w - insetX, insetY, -1 to 1),
            Triple(insetX, h - insetY, 1 to -1),
            Triple(w - insetX, h - insetY, -1 to -1),
        ).forEach { (x, y, dirs) ->
            drawLine(bracket, Offset(x, y), Offset(x + dirs.first * len, y), 5f, cap = StrokeCap.Round)
            drawLine(bracket, Offset(x, y), Offset(x, y + dirs.second * len), 5f, cap = StrokeCap.Round)
        }
    }
}
