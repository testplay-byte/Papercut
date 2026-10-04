package com.papercut.app.core.domain

import android.graphics.Bitmap
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.Quad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An in-progress capture session ("draft"). The scanner produces pages; the
 * crop/filter editor mutates them; "save" hands the whole draft to
 * DocumentRepository and clears it.
 *
 * App-scoped (not a ViewModel) because navigation between scanner and editor
 * must not destroy unsaved captures — the old per-screen remember-state design
 * lost everything on rotation/back.
 */
class DraftStore {

    data class DraftPage(
        val bitmap: Bitmap,       // ORIGINAL upright capture (edits stored separately)
        val quad: Quad? = null,
        val rotation: Int = 0,
        val filter: PageFilter = PageFilter.MAGIC,
        val mode: com.papercut.app.core.data.model.ScanMode =
            com.papercut.app.core.data.model.ScanMode.TEXT, // digitize mode for this page
    )

    private val _pages = MutableStateFlow<List<DraftPage>>(emptyList())
    val pages: StateFlow<List<DraftPage>> = _pages.asStateFlow()

    /** Folder the draft started in — resume saves there, not wherever Library sits. */
    private val _folder = MutableStateFlow<String?>(null)
    val folder: StateFlow<String?> = _folder.asStateFlow()

    /** suggested doc name for the sheet (persisted across editor round-trips) */
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    fun setName(n: String) { _name.value = n }

    /** Take ownership for a fresh session; ignored if a draft already exists. */
    fun claimFolder(f: String) {
        if (_pages.value.isEmpty()) _folder.value = f
    }

    fun addPage(bmp: Bitmap, mode: com.papercut.app.core.data.model.ScanMode =
        com.papercut.app.core.data.model.ScanMode.TEXT) {
        _pages.value = _pages.value + DraftPage(bmp, mode = mode)
    }

    fun updatePage(index: Int, transform: (DraftPage) -> DraftPage) {
        val list = _pages.value.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _pages.value = list
        }
    }

    fun removePage(index: Int) {
        val list = _pages.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index).bitmap.recycle()
            _pages.value = list
        }
    }

    fun movePage(from: Int, to: Int) {
        val list = _pages.value.toMutableList()
        if (from in list.indices && to in list.indices && from != to) {
            list.add(to, list.removeAt(from))
            _pages.value = list
        }
    }

    /** Full wipe (recycles bitmaps) — used after save or explicit discard. */
    fun clear() {
        _pages.value.forEach { it.bitmap.recycle() }
        _pages.value = emptyList()
        _name.value = ""
        _folder.value = null
    }

    val isEmpty: Boolean get() = _pages.value.isEmpty()
}
