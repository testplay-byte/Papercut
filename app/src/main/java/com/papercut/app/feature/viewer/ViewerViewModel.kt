package com.papercut.app.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.ScanItem
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which HTML the viewer shows right now. */
enum class VersionView { CURRENT, BACKUP }

/**
 * Viewer state for one scan: image + digitized HTML, single-backup version
 * handling, re-improve with feedback, and high-res PNG export.
 *
 * Old app's viewer was a 1,100-line god-composable with a web of interdependent
 * LaunchedEffects. Here every effect is replaced by explicit user actions +
 * flows owned by this ViewModel.
 */
class ViewerViewModel(
    private val container: AppContainer,
    private val appContext: Context,
    val folderName: String,
    val scanName: String,
) : ViewModel() {

    private val _item = MutableStateFlow<ScanItem?>(null)
    val item: StateFlow<ScanItem?> = _item.asStateFlow()

    private val _view = MutableStateFlow(VersionView.CURRENT)
    val versionView: StateFlow<VersionView> = _view.asStateFlow()

    private val _html = MutableStateFlow<String?>(null)
    val html: StateFlow<String?> = _html.asStateFlow()

    /** true = show the digitized HTML, false = show the photo */
    private val _showHtml = MutableStateFlow(false)
    val showHtml: StateFlow<Boolean> = _showHtml.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val status: StateFlow<ScanStatus> = container.queue.statuses
        .map { map ->
            map["$folderName/$scanName"]?.status ?: ScanStatus.Plain
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanStatus.Plain)

    val canRetry: Boolean get() = status.value == ScanStatus.Error

    init {
        refreshItem()
    }

    fun refreshItem() = viewModelScope.launch {
        val scans = container.scans.listScans(folderName)
        _item.value = scans.find { it.name == scanName }
        loadHtml()
    }

    /** When processing completes, pick up the new file automatically. */
    fun onStatusBecameDone() {
        _showHtml.value = true
        refreshItem()
    }

    fun setShowHtml(show: Boolean) { _showHtml.value = show }

    fun switchVersion(view: VersionView) {
        _view.value = view
        loadHtml()
    }

    private fun loadHtml() = viewModelScope.launch {
        val it0 = _item.value ?: return@launch
        _html.value = container.scans.readHtml(it0, backup = _view.value == VersionView.BACKUP)
    }

    /** Re-run the AI with optional user feedback; current HTML becomes the backup. */
    fun reImprove(mode: ScanMode, feedback: String?) {
        val item = _item.value ?: return
        container.queue.clearError(item)
        container.queue.submit(item, mode, feedback)
        _view.value = VersionView.CURRENT
    }

    fun keepCurrent() = viewModelScope.launch {
        _item.value?.let { container.scans.discardBackup(it) }
        refreshItem()
    }

    fun restoreBackup() = viewModelScope.launch {
        val item = _item.value ?: return@launch
        val ok = container.scans.restoreBackup(item)
        if (!ok) _message.value = "Restore failed — file may have been moved"
        _view.value = VersionView.CURRENT
        refreshItem()
    }

    /** Render current HTML at A4 resolution and save the PNG to the gallery. */
    fun exportHighRes() = viewModelScope.launch {
        val item = _item.value ?: return@launch
        val htmlText = _html.value ?: return@launch
        _exporting.value = true
        try {
            val bitmap = container.renderer.render(htmlText)
            if (bitmap == null) {
                _message.value = "Export failed — the page didn't render in time"
            } else {
                val saved = withContext(Dispatchers.IO) {
                    saveToPictures(appContext, bitmap, "${item.name}_digital")
                }
                _message.value = if (saved) "Saved to Pictures as PNG" else "Could not write to Pictures"
            }
        } finally {
            _exporting.value = false
        }
    }

    fun shareImage(callback: (uri: String?) -> Unit) = viewModelScope.launch {
        val item = _item.value ?: return@launch callback(null)
        val uri = withContext(Dispatchers.IO) {
            // share the current HTML rendered? no — share the ORIGINAL photo for simplicity;
            // HTML share goes through cache file + FileProvider
            try {
                val htmlText = container.scans.readHtml(item, backup = _view.value == VersionView.BACKUP)
                if (htmlText == null) return@withContext null
                val dir = java.io.File(appContext.cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "${item.name}.html")
                file.writeText(htmlText)
                androidx.core.content.FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.fileprovider", file
                ).toString()
            } catch (_: Exception) {
                null
            }
        }
        callback(uri)
    }

    fun consumeMessage() { _message.value = null }

    private fun saveToPictures(context: Context, bitmap: Bitmap, title: String): Boolean {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$title.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Papercut")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
