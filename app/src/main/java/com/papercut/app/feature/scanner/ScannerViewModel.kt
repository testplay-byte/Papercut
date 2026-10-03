package com.papercut.app.feature.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.ScanItem
import com.papercut.app.core.data.model.ScanMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Flash { OFF, ON, AUTO }

/**
 * Scanner state. Deliberately tiny: the camera preview itself is a Compose
 * AndroidView concern; everything that must survive navigation lives here.
 */
class ScannerViewModel(
    private val container: AppContainer,
    val folderName: String,
) : ViewModel() {

    private val _flash = MutableStateFlow(Flash.OFF)
    val flash: StateFlow<Flash> = _flash

    private val _mode = MutableStateFlow(ScanMode.TEXT)
    val mode: StateFlow<ScanMode> = _mode

    private val _batch = MutableStateFlow(false)
    val batchMode: StateFlow<Boolean> = _batch

    private val _batchCount = MutableStateFlow(0)
    val batchCount: StateFlow<Int> = _batchCount

    /** true while the shutter is locked (capture in flight). */
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    /** Live processing overlay info (single source — no stacked overlays). */
    val queueStatuses: StateFlow<Map<String, com.papercut.app.core.domain.ProcessingQueue.TaskState>> =
        container.queue.statuses.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyMap(),
        )

    sealed interface ScanEvent {
        data class Saved(val item: ScanItem) : ScanEvent
        data class Error(val message: String) : ScanEvent
    }

    /** Name of the most recent capture this session (shutter shows a check, gallery opens it). */
    var lastSavedName: String? = null
        private set

    fun setFlash(f: Flash) { _flash.value = f }
    fun setMode(m: ScanMode) { _mode.value = m }
    fun setBatch(b: Boolean) {
        _batch.value = b
        if (!b) _batchCount.value = 0
    }

    fun beginCapture() { _capturing.value = true }
    fun endCapture() { _capturing.value = false }
    fun captureFailed(message: String) {
        _capturing.value = false
        _events.tryEmit(ScanEvent.Error(message))
    }

    fun onCaptureFailed(message: String) = captureFailed(message)

    /** Store a freshly captured bitmap (rotation already applied by CameraX). */
    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val item = container.scans.saveCapture(folderName, bitmap)
                if (item == null) {
                    _events.tryEmit(ScanEvent.Error("Could not save the scan — check storage access in Settings"))
                    return@launch
                }
                bitmap.recycle()
                lastSavedName = item.name
                _batchCount.value += 1
                _events.tryEmit(ScanEvent.Saved(item))

                val settings = container.settings.settings.value
                if (settings.autoEnhance) {
                    container.queue.submit(item, _mode.value)
                }
            } catch (e: Exception) {
                _events.tryEmit(ScanEvent.Error(e.message ?: "Capture failed"))
            } finally {
                _capturing.value = false
            }
        }
    }

}
