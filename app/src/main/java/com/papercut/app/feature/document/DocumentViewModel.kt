package com.papercut.app.feature.document

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.PdfExporter
import com.papercut.app.core.data.model.PageSpec
import com.papercut.app.core.data.model.PageView
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

/**
 * One open document: pages, renders (LRU-cached), AI status, version ops,
 * and export (PDF / 4K PNG / share). All disk + image work off the main thread.
 */
class DocumentViewModel(
    private val container: AppContainer,
    val folderName: String,
    val docName: String,
) : ViewModel() {

    sealed interface Ui {
        data object Loading : Ui
        data object NotFound : Ui
        data class Ready(val pages: List<PageView>) : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** per-page AI status, live from the queue (single source) */
    val statuses: StateFlow<Map<String, com.papercut.app.core.domain.ProcessingQueue.TaskState>> =
        container.queue.statuses

    private val _htmlFor = MutableStateFlow<Pair<Int, Boolean>?>(null)
    private val _htmlContent = MutableStateFlow<String?>(null)
    val htmlContent: StateFlow<String?> = _htmlContent.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null) // export progress label
    val busy: StateFlow<String?> = _busy.asStateFlow()

    // ---- render cache: pageIndex -> bitmap (edited view). ----
    // Entries are DROPPED, never recycled: composables hold references and a
    // recycle on eviction/refresh would crash the next draw ("recycled bitmap").
    // GC reclaims the native pixels; the cap keeps a handful of warm pages.
    private val renderCache = LinkedHashMap<Int, Bitmap>()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val summaries = container.docs.listDocuments(folderName)
        val summary = summaries.find { it.name == docName }
        if (summary == null) { _ui.value = Ui.NotFound; return@launch }
        val pages = container.docs.getPages(summary)
        renderCache.clear()
        _ui.value = Ui.Ready(pages)
    }

    /** Rendered (crop+rotate+filter) bitmap for a page — cached. Treat as read-only. */
    suspend fun renderPage(page: PageView): Bitmap? = withContext(Dispatchers.Default) {
        renderCache[page.spec.index]?.let { return@withContext it }
        val raw = decodeSampled(page.imageUri, 1800) ?: return@withContext null
        val out = com.papercut.app.core.data.PagePipeline.render(raw, page.spec)
        putCache(page.spec.index, out)
        out
    }

    /** Small poster for thumbnails/filmstrip (cheap decode, no edits). */
    suspend fun thumbnail(page: PageView): Bitmap? = withContext(Dispatchers.Default) {
        decodeSampled(page.imageUri, 240)
    }

    private fun putCache(index: Int, bmp: Bitmap) {
        renderCache[index] = bmp
        while (renderCache.size > 5) renderCache.remove(renderCache.keys.first())
    }

    private suspend fun decodeSampled(uriStr: String, maxSide: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(uriStr)
                val resolver = container.appContext.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Exception) {
                null
            }
        }

    // ---------------- AI per page ----------------

    fun digitize(page: PageView, mode: ScanMode, feedback: String?) {
        container.queue.clearError(page.folder, page.docName, page.spec.index)
        container.queue.submit(page, mode, feedback)
    }

    fun cancelDigitize(page: PageView) =
        container.queue.cancel(page.folder, page.docName, page.spec.index)

    fun statusOf(page: PageView): ScanStatus =
        statuses.value[com.papercut.app.core.domain.ProcessingQueue.statusKey(
            page.folder, page.docName, page.spec.index,
        )]?.status ?: ScanStatus.Plain

    // ---------------- versions ----------------

    fun showHtml(page: PageView, backup: Boolean) = viewModelScope.launch {
        _htmlFor.value = page.spec.index to backup
        _htmlContent.value = container.docs.readPageHtml(page, backup)
    }

    fun closeHtml() {
        _htmlFor.value = null
        _htmlContent.value = null
    }

    val showingBackup: Boolean get() = _htmlFor.value?.second == true

    fun restoreBackup(page: PageView) = viewModelScope.launch {
        val ok = container.docs.restorePageBackup(page.folder, page.docName, page.spec.index)
        container.messages.post(if (ok) "Restored previous version" else "No backup to restore")
        refresh()
    }

    fun discardBackup(page: PageView) = viewModelScope.launch {
        container.docs.discardPageBackup(page.folder, page.docName, page.spec.index)
        refresh()
    }

    // ---------------- page ops ----------------

    /** Move current page earlier/later; returns new index. */
    fun movePage(pages: List<PageView>, from: Int, delta: Int) = viewModelScope.launch {
        val order = pages.map { it.spec.index }.toMutableList()
        val pos = order.indexOf(from)
        val to = (pos + delta).coerceIn(0, order.size - 1)
        if (pos == -1 || pos == to) return@launch
        order.add(to, order.removeAt(pos))
        if (container.docs.setOrder(folderName, docName, order)) {
            container.messages.post("Page $from → ${to + 1}")
            refresh()
        }
    }

    fun deletePage(page: PageView) = viewModelScope.launch {
        container.queue.cancel(page.folder, page.docName, page.spec.index)
        if (container.docs.deletePage(folderName, docName, page.spec.index)) refresh()
        else container.messages.post("Could not delete page")
    }

    /** Save one rotated edit on an existing doc page (used by page quick-rotate). */
    fun rotatePage(page: PageView) = viewModelScope.launch {
        val ok = container.docs.updateMeta(folderName, docName) { meta ->
            meta.copy(pages = meta.pages.map {
                if (it.index == page.spec.index) it.copy(rotation = (it.rotation + 90) % 360) else it
            })
        }
        if (ok) refresh() else container.messages.post("Could not rotate page")
    }

    /** Change a saved page's filter in place (meta-only, non-destructive). */
    fun setPageFilter(page: PageView, filter: com.papercut.app.core.data.model.PageFilter) = viewModelScope.launch {
        val ok = container.docs.updateMeta(folderName, docName) { meta ->
            meta.copy(pages = meta.pages.map {
                if (it.index == page.spec.index) it.copy(filter = filter) else it
            })
        }
        if (ok) refresh()
    }

    // ---------------- export ----------------

    fun exportPdf(share: Boolean, onUri: (android.net.Uri?) -> Unit) = viewModelScope.launch {
        val pages = (_ui.value as? Ui.Ready)?.pages ?: return@launch
        _busy.value = "Building PDF…"
        val uri = withContext(Dispatchers.Default) {
            try {
                val bitmaps = pages.mapNotNull { p ->
                    renderPage(p)?.let { bmp ->
                        val flat = com.papercut.app.core.data.PagePipeline.flattenForPdf(bmp)
                        if (flat !== bmp) flat.also { } // keep both: bmp cached, flat for pdf
                        flat
                    }
                }
                if (bitmaps.isEmpty()) null
                else {
                    val bytes = PdfExporter.build(bitmaps)
                    bitmaps.forEach { runCatching { it.recycle() } }
                    val ctx = container.appContext
                    if (share) PdfExporter.cacheForShare(ctx, bytes, docName)
                    else PdfExporter.saveToDownloads(ctx, bytes, docName)
                }
            } catch (_: Exception) {
                null
            }
        }
        _busy.value = null
        onUri(uri)
        container.messages.post(
            if (uri != null) "PDF ready — Downloads/Papercut" else "PDF export failed",
        )
    }

    /** 4K PNG of a page's digitized HTML (event-driven render). */
    fun exportHtmlAsImage(page: PageView) = viewModelScope.launch {
        val html = container.docs.readPageHtml(page, backup = false)
        if (html == null) { container.messages.post("Nothing digitized for this page yet"); return@launch }
        _busy.value = "Rendering 4K image…"
        val bitmap = container.renderer.render(html)
        if (bitmap == null) {
            _busy.value = null
            container.messages.post("Page did not render in time")
            return@launch
        }
        val saved = withContext(Dispatchers.IO) {
            savePictures(container.appContext, bitmap, "$docName-p${page.spec.index}")
        }
        bitmap.recycle()
        _busy.value = null
        container.messages.post(if (saved) "4K PNG saved to Pictures/Papercut" else "Could not save image")
    }

    fun shareOriginal(page: PageView, onUri: (String?) -> Unit) = viewModelScope.launch {
        onUri(if (page.imageUri.isNotBlank()) page.imageUri else null)
    }

    private fun savePictures(context: Context, bitmap: Bitmap, title: String): Boolean {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$title.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_PICTURES}/Papercut")
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
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
