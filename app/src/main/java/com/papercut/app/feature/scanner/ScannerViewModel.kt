package com.papercut.app.feature.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.PageEdits
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.domain.DraftStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Flash { OFF, ON, AUTO }

/**
 * Scanner state for one capture session. Pages live in the app-scoped
 * [DraftStore] so they survive navigation to the crop editor; saving turns
 * the draft into a Document (new, or appended to an existing one).
 */
class ScannerViewModel(
    private val container: AppContainer,
    val folderName: String,
    val appendToDoc: String?,      // null = new document
) : ViewModel() {

    val drafts: DraftStore = container.drafts
    val pages: StateFlow<List<DraftStore.DraftPage>> = drafts.pages

    private val _flash = MutableStateFlow(Flash.OFF)
    val flash: StateFlow<Flash> = _flash.asStateFlow()

    private val _mode = MutableStateFlow(container.settings.settings.value.defaultScanMode)
    val mode: StateFlow<ScanMode> = _mode.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val suggestedName: StateFlow<String> = drafts.name

    init {
        // claim the folder for a brand-new session (resumed drafts keep theirs)
        drafts.claimFolder(folderName)
        // fresh session starts with a name suggestion (existing drafts keep theirs)
        if (drafts.pages.value.isEmpty() && drafts.name.value.isEmpty()) {
            drafts.setName(container.docs.suggestName())
        }
    }

    fun setFlash(f: Flash) { _flash.value = f }
    fun setMode(m: ScanMode) { _mode.value = m }
    fun setName(n: String) { drafts.setName(n) }

    fun beginCapture() { _capturing.value = true }
    fun captureFailed(msg: String) {
        _capturing.value = false
        container.messages.post(msg)
    }

    /** Persist one upright capture into the draft (carries the session's mode). */
    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                drafts.addPage(bitmap, _mode.value)
            } finally {
                _capturing.value = false
            }
        }
    }

    fun removePage(index: Int) = drafts.removePage(index)

    fun discard() = drafts.clear()

    /**
     * Save the whole draft: append to [appendToDoc] when set, else create a
     * new document. Per-page crop/rotation/filter edits are carried along.
     */
    fun saveDraft(onDone: (folder: String?, docName: String?, ok: Boolean) -> Unit) {
        val current = drafts.pages.value
        if (current.isEmpty()) { onDone(null, null, false); return }
        _saving.value = true
        // draft owns its folder (resume may come from a different scope)
        val targetFolder = drafts.folder.value ?: folderName
        viewModelScope.launch {
            val bits = current.map { it.bitmap }
            val edits = current.map { PageEdits(it.quad, it.rotation, it.filter, it.mode) }
            val name = drafts.name.value.ifBlank { container.docs.suggestName() }
            val result: String? = if (appendToDoc != null) {
                if (container.docs.addPages(targetFolder, appendToDoc, bits, edits)) appendToDoc else null
            } else {
                container.docs.createDocument(targetFolder, bits, name, edits)?.second
            }
            if (result != null) {
                val count = current.size
                drafts.clear() // recycles bitmaps AFTER successful write
                container.messages.post(if (appendToDoc != null) "Added $count pages" else "Saved “$result”")
                // honor the Auto-digitize switch: queue new pages with their
                // own Text/Notes mode (the scanner pill now means something)
                if (container.settings.settings.value.autoEnhance) {
                    val summary = container.docs.listDocuments(targetFolder).find { it.name == result }
                    if (summary != null) {
                        container.docs.getPages(summary)
                            .filter { it.htmlUri == null }
                            .forEach { p -> container.queue.submit(p, p.spec.aiMode) }
                    }
                }
            } else {
                container.messages.post("Could not save — check storage access in Settings")
            }
            _saving.value = false
            onDone(targetFolder, result, result != null)
        }
    }
}
