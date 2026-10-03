package com.papercut.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.LibraryFolder
import com.papercut.app.core.data.model.ScanItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Library state: folders + a couple of global totals for the header bento tiles. */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val folders: StateFlow<List<LibraryFolder>> = _folders.asStateFlow()

    val totalScans: StateFlow<Int> = _folders
        .map { list -> list.sumOf { it.scanCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val digitizedScans: StateFlow<Int> = _folders
        .map { list -> list.sumOf { it.digitizedCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _folders.value = container.scans.listFolders()
        }
    }

    fun createFolder(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = container.scans.createFolder(name)
        if (ok) refresh() else _message.value = "Couldn't create \"$name\""
        onDone(ok)
    }

    fun renameFolder(old: String, new: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = container.scans.renameFolder(old, new)
        if (ok) refresh() else _message.value = "Couldn't rename folder"
        onDone(ok)
    }

    fun deleteFolder(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = container.scans.deleteFolder(name)
        if (ok) refresh() else _message.value = if (name == "Default") "Default folder can't be deleted" else "Couldn't delete folder"
        onDone(ok)
    }

    fun consumeMessage() { _message.value = null }
}

/** Per-folder screen state. */
class FolderViewModel(
    private val container: AppContainer,
    val folderName: String,
) : ViewModel() {

    private val _scans = MutableStateFlow<List<ScanItem>>(emptyList())
    val scans: StateFlow<List<ScanItem>> = _scans.asStateFlow()

    /** live per-scan processing status (key "folder/name") — single source for badges */
    val statuses: StateFlow<Map<String, com.papercut.app.core.domain.ProcessingQueue.TaskState>> =
        container.queue.statuses

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _scans.value = container.scans.listScans(folderName)
    }

    fun deleteScan(item: ScanItem) = viewModelScope.launch {
        container.queue.cancel(item)
        container.scans.deleteScan(item)
        refresh()
    }
}
