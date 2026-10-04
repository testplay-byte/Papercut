package com.papercut.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.DocumentRepository
import com.papercut.app.core.data.model.DocumentSummary
import com.papercut.app.core.data.model.ScanMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DocSort { RECENT, NAME }

/**
 * Library state: folders (chips), documents in scope, search/sort, multi-select.
 * A single VM serves both the all-folders view and a folder-scoped view.
 */
class LibraryViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<DocumentRepository.FolderInfo>>(emptyList())
    val folders: StateFlow<List<DocumentRepository.FolderInfo>> = _folders.asStateFlow()

    private val _docs = MutableStateFlow<List<DocumentSummary>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _sort = MutableStateFlow(DocSort.RECENT)

    val query: StateFlow<String> = _query.asStateFlow()
    val sort: StateFlow<DocSort> = _sort.asStateFlow()

    val visibleDocs: StateFlow<List<DocumentSummary>> =
        combine(_docs, _query, _sort) { docs, q, sort ->
            val filtered = if (q.isBlank()) docs
            else docs.filter { it.name.contains(q, ignoreCase = true) }
            when (sort) {
                DocSort.RECENT -> filtered.sortedByDescending { it.lastActivityAt }
                DocSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- multi-select ----
    private val _selected = MutableStateFlow<Set<Pair<String, String>>>(emptySet()) // (folder,name)
    val selected: StateFlow<Set<Pair<String, String>>> = _selected.asStateFlow()
    val selectionMode: StateFlow<Boolean> = _selected
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleSelect(folder: String, name: String) {
        val key = folder to name
        _selected.value = if (key in _selected.value) _selected.value - key
        else _selected.value + key
    }

    fun clearSelection() { _selected.value = emptySet() }

    // ---- data ----

    /** Unsaved capture session (drafts survive navigation) — for the resume banner. */
    val draftPages: StateFlow<Int> = container.drafts.pages
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val draftFolder: StateFlow<String?> = container.drafts.folder

    fun discardDraft() = container.drafts.clear()

    /** Load data on (re)enter; VM survives scope switch via key in appViewModel… */
    fun load(scope: String?) = viewModelScope.launch {
        _folders.value = container.docs.listFolders()
        _docs.value = if (scope == null) {
            _folders.value.flatMap { container.docs.listDocuments(it.name) }
        } else {
            container.docs.listDocuments(scope)
        }
        _selected.value = emptySet()
    }

    fun setQuery(q: String) { _query.value = q }
    fun toggleSort() { _sort.value = if (_sort.value == DocSort.RECENT) DocSort.NAME else DocSort.RECENT }

    fun createFolder(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = container.docs.createFolder(name)
        if (ok) load(null) else container.messages.post("Could not create folder")
        onDone(ok)
    }

    fun renameFolder(old: String, new: String) = viewModelScope.launch {
        if (container.docs.renameFolder(old, new)) load(null)
        else container.messages.post("Could not rename folder")
    }

    fun deleteFolder(name: String) = viewModelScope.launch {
        if (container.docs.deleteFolder(name)) load(null)
        else container.messages.post(if (name == DocumentRepository.DEFAULT_FOLDER)
            "Default folder can't be deleted" else "Could not delete folder")
    }

    fun renameDocument(doc: DocumentSummary, newName: String) = viewModelScope.launch {
        if (container.docs.renameDocument(doc.folder, doc.name, newName)) load(null)
        else container.messages.post("Could not rename — legacy scans can't be renamed")
    }

    fun deleteDocument(doc: DocumentSummary) = viewModelScope.launch {
        container.queue.cancelDocument(doc.folder, doc.name)
        if (container.docs.deleteDocument(doc)) load(null)
        else container.messages.post("Could not delete")
    }

    /** Digitize every not-yet-done page of the selected documents. */
    fun digitizeSelected(scope: String?) = viewModelScope.launch {
        val targets = _docs.value.filter { (it.folder to it.name) in _selected.value }
        var submitted = 0
        targets.forEach { s ->
            val pages = container.docs.getPages(s)
            pages.filter { it.htmlUri == null }.forEach { p ->
                container.queue.submit(p, ScanMode.TEXT); submitted++
            }
        }
        container.messages.post(if (submitted > 0) "$submitted page(s) queued" else "Nothing new to digitize")
        _selected.value = emptySet()
    }

    fun deleteSelected() = viewModelScope.launch {
        val targets = _docs.value.filter { (it.folder to it.name) in _selected.value }
        targets.forEach { s ->
            container.queue.cancelDocument(s.folder, s.name)
            container.docs.deleteDocument(s)
        }
        _selected.value = emptySet()
        load(scope = null)
    }

    /** Total pages queued/working right now for the live chip. */
    val queueActive: StateFlow<Int> = container.queue.statuses
        .map { m ->
            m.values.count {
                it.status == com.papercut.app.core.design.ScanStatus.Queued ||
                    it.status == com.papercut.app.core.design.ScanStatus.Processing
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
