package com.papercut.app.feature.scanner

import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.ScanStatus
import com.papercut.app.core.design.SegmentedPill
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.core.domain.OrientationTracker
import com.papercut.app.di.appViewModel
import java.io.File
import java.util.concurrent.Executors

/**
 * Scanner screen — the one place the bento canvas flips to night focus mode.
 *
 * Layout referenced from the old app's scanner (its best screen): top control
 * pills, rule-of-thirds viewfinder with corner brackets, bottom shutter row.
 *
 * Re-engineered:
 *  - flash is a LIVE setter on ImageCapture (the old app re-bound the whole
 *    camera per flash tap: preview flicker + a brand-new ImageCapture each time)
 *  - orientation flows through OrientationTracker: a smoothed continuous angle
 *    drives the soft UI follow, and only the STABLE quadrant reaches
 *    ImageCapture.targetRotation — pixels never see the 45° wobble
 *  - all state lives in ScannerViewModel; this file is pure presentation
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    folderName: String,
    onDone: () -> Unit,
    onOpenLatest: (String) -> Unit,
) {
    val vm: ScannerViewModel = appViewModel { c -> ScannerViewModel(c, folderName) }
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current

    // one-time error toast text (kept here so the VM stays UI-free)
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ScannerViewModel.ScanEvent.Error -> toast = event.message
                is ScannerViewModel.ScanEvent.Saved -> if (!vm.batchMode.value) {
                    onOpenLatest(event.item.name) // single mode goes straight to the viewer
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.NightCanvas)
            .systemBarsPadding(),
    ) {
        if (!cameraPermission.status.isGranted) {
            PermissionNudge(onAllow = { cameraPermission.launchPermissionRequest() })
            return@Box
        }

        val flash by vm.flash.collectAsState()
        val mode by vm.mode.collectAsState()
        val batch by vm.batchMode.collectAsState()
        val batchCount by vm.batchCount.collectAsState()
        val capturing by vm.capturing.collectAsState()
        val statuses by vm.queueStatuses.collectAsState()

        // ---- smooth orientation ----
        val tracker = remember { OrientationTracker() }
        var smoothedDeg by remember { mutableFloatStateOf(0f) }
        DisposableEffect(Unit) {
            val listener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    tracker.onRawAngle(deg.toFloat())
                    smoothedDeg = tracker.smoothedDeg
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }

        // ---- camera: bound ONCE for the screen's lifetime ----
        var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
        val previewView = remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            onDispose {
                provider.unbindAll()
                imageCapture = null
            }
        }

        // live flash — one property write, zero rebinds
        LaunchedEffect(flash) {
            imageCapture?.flashMode = when (flash) {
                Flash.OFF -> ImageCapture.FLASH_MODE_OFF
                Flash.ON -> ImageCapture.FLASH_MODE_ON
                Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
            }
        }

        // live target rotation — the tracker's HYSTERESIS-STABLE quadrant
        LaunchedEffect(tracker.targetRotation) {
            imageCapture?.targetRotation = tracker.targetRotation
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        ViewfinderOverlay()

        // ---------------- top controls ----------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = PaperGap.l, vertical = PaperGap.m),
            verticalArrangement = Arrangement.spacedBy(PaperGap.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                FlashButton(flash) {
                    vm.setFlash(
                        when (flash) {
                            Flash.OFF -> Flash.ON
                            Flash.ON -> Flash.AUTO
                            Flash.AUTO -> Flash.OFF
                        }
                    )
                }
                SegmentedPill(
                    options = listOf(ScanMode.TEXT to "Text", ScanMode.NOTES to "Notes"),
                    selected = mode,
                    onSelect = { vm.setMode(it) },
                    darkSurface = true,
                    modifier = Modifier.width(190.dp),
                )
            }

            SegmentedPill(
                options = listOf(false to "Single", true to "Batch"),
                selected = batch,
                onSelect = { vm.setBatch(it) },
                darkSurface = true,
                modifier = Modifier.width(190.dp),
            )

            if (batch && batchCount > 0) {
                Text("$batchCount captured", color = PaperColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            // live queue pill — ONE status surface fed by the statuses map
            val activeCount = statuses.values.count { it.status == ScanStatus.Queued || it.status == ScanStatus.Processing }
            if (activeCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(PaperColors.NightTile, RoundedCornerShape(PaperRadii.pill))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = PaperColors.Accent, modifier = Modifier.size(12.dp))
                    Text("Digitizing $activeCount…", color = PaperColors.NightInkSecondary, fontSize = 12.sp)
                }
            }
        }

        // ---------------- shutter row ----------------
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val libInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .pressScale(libInteraction)
                    .background(PaperColors.NightTile, CircleShape)
                    .tap(libInteraction) {
                        val last = vm.lastSavedName
                        if (last != null) onOpenLatest(last) else onDone()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PhotoLibrary, "Done", tint = PaperColors.NightInk, modifier = Modifier.size(24.dp))
            }

            val shutterInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .pressScale(shutterInteraction, pressedScale = if (capturing) 1f else 0.93f)
                    .background(PaperColors.NightTile, CircleShape)
                    .padding(5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(if (capturing) PaperColors.InkFaint else Color.White, CircleShape)
                        .tap(shutterInteraction) {
                            if (!capturing) {
                                vm.beginCapture()
                                takePicture(context, imageCapture, vm)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        capturing -> CircularProgressIndicator(strokeWidth = 3.dp, color = PaperColors.Accent, modifier = Modifier.size(24.dp))
                        vm.lastSavedName != null && !batch -> Icon(Icons.Filled.Check, null, tint = PaperColors.Accent, modifier = Modifier.size(26.dp))
                    }
                }
            }

            // soft angle readout: rotates gently with the smoothed angle
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (mode == ScanMode.TEXT) "Text" else "Notes",
                    color = PaperColors.NightInkSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.rotate(-smoothedDeg * 0.35f),
                )
            }
        }

        // toast (self-dismissing)
        toast?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2600)
                toast = null
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp)
                    .background(PaperColors.Error, RoundedCornerShape(PaperRadii.small))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FlashButton(flash: Flash, onCycle: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val icon = when (flash) {
        Flash.OFF -> Icons.Filled.FlashOff
        Flash.ON -> Icons.Filled.FlashOn
        Flash.AUTO -> Icons.Filled.FlashAuto
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressScale(interaction)
            .background(
                if (flash == Flash.OFF) PaperColors.NightTile else PaperColors.Accent,
                CircleShape,
            )
            .tap(interaction, onCycle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "Flash: $flash", tint = if (flash == Flash.OFF) PaperColors.NightInk else Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PermissionNudge(onAllow: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PaperGap.m, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize(),
    ) {
        Text("Camera access makes Papercut work.", color = PaperColors.NightInk, fontSize = 16.sp)
        Text("Tap allow, then come back.", color = PaperColors.NightInkSecondary, fontSize = 13.sp)
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            modifier = Modifier
                .pressScale(interaction)
                .background(PaperColors.Accent, RoundedCornerShape(PaperRadii.pill))
                .tap(interaction, onAllow)
                .padding(horizontal = 28.dp, vertical = 12.dp),
        ) {
            Text("Allow camera", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Capture to a temp file. CameraX applies targetRotation itself, so the saved
 * JPEG is upright per the STABLE quadrant. We downsample-decode for memory
 * safety and hand the bitmap to the ViewModel.
 */
private fun takePicture(
    context: Context,
    imageCapture: ImageCapture?,
    vm: ScannerViewModel,
) {
    val capture = imageCapture ?: run { vm.endCapture(); return }
    val tmp = File(context.cacheDir, "papercut_capture.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(tmp).build()
    capture.takePicture(
        options,
        Executors.newSingleThreadExecutor(),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 2200)
                }
                val bmp = BitmapFactory.decodeFile(tmp.path, opts)
                tmp.delete()
                if (bmp == null) vm.captureFailed("Could not read the captured photo")
                else vm.onImageCaptured(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                tmp.delete()
                vm.captureFailed("Capture failed (${exception.imageCaptureError})")
            }
        },
    )
}

private fun sampleSizeFor(w: Int, h: Int, maxSide: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** Rule-of-thirds grid + corner brackets. Pure Canvas — no image assets. */
@Composable
private fun ViewfinderOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val grid = Color.White.copy(alpha = 0.20f)
        for (i in 1..2) {
            drawLine(grid, Offset(w * i / 3f, h * 0.14f), Offset(w * i / 3f, h * 0.86f), 1.5f)
            drawLine(grid, Offset(w * 0.08f, h * i / 3f), Offset(w * 0.92f, h * i / 3f), 1.5f)
        }
        val bracket = Color.White.copy(alpha = 0.85f)
        val len = 46f
        val insetX = w * 0.07f
        val insetY = h * 0.15f
        val corners = listOf(
            Triple(insetX, insetY, 1 to 1),
            Triple(w - insetX, insetY, -1 to 1),
            Triple(insetX, h - insetY, 1 to -1),
            Triple(w - insetX, h - insetY, -1 to -1),
        )
        corners.forEach { (x, y, dirs) ->
            drawLine(bracket, Offset(x, y), Offset(x + dirs.first * len, y), 6f, cap = StrokeCap.Round)
            drawLine(bracket, Offset(x, y), Offset(x, y + dirs.second * len), 6f, cap = StrokeCap.Round)
        }
    }
}
