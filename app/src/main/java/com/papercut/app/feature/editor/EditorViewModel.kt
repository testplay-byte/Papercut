package com.papercut.app.feature.editor

import androidx.lifecycle.ViewModel
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.Quad
import com.papercut.app.core.domain.DraftStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Edit state for one draft page. We keep the working copy in the VM (handles
 * drag at 60fps) and commit to the DraftStore only on Apply — back/cancel
 * discards the edits, Apply saves them.
 */
class EditorViewModel(
    private val container: AppContainer,
    private val draftIndex: Int,
) : ViewModel() {

    private val initial: DraftStore.DraftPage? =
        container.drafts.pages.value.getOrNull(draftIndex)

    private val _state = MutableStateFlow(
        initial?.let { EditorState(it.bitmap, it.quad, it.rotation, it.filter) },
    )
    val state: StateFlow<EditorState?> = _state.asStateFlow()

    data class EditorState(
        val bitmap: android.graphics.Bitmap,
        val quad: Quad?,
        val rotation: Int,
        val filter: PageFilter,
    )

    fun setQuad(q: Quad) { _state.value = _state.value?.let { it.copy(quad = if (q.isFullPage) null else q) } }
    fun clearQuad() { _state.value = _state.value?.let { it.copy(quad = null) } }

    fun rotate() {
        _state.value = _state.value?.let {
            it.copy(
                rotation = (it.rotation + 90) % 360,
                quad = it.quad?.rotated90(),
            )
        }
    }

    fun setFilter(f: PageFilter) { _state.value = _state.value?.let { it.copy(filter = f) } }

    /** Commit current edits into the shared DraftStore page. */
    fun applyAndClose() {
        val s = _state.value ?: return
        container.drafts.updatePage(draftIndex) {
            it.copy(quad = s.quad, rotation = s.rotation, filter = s.filter)
        }
    }
}
