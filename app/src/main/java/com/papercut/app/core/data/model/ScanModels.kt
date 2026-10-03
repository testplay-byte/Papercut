package com.papercut.app.core.data.model

/** A scanned document as the library/viewer see it. Paths are SAF uri strings. */
data class ScanItem(
    val folder: String,
    val name: String,        // stable id, e.g. IMG_20261004_153000
    val imageUri: String,
    val htmlUri: String?,    // current digitized version (null = not yet processed)
    val backupUri: String?,  // previous version kept for restore
)

/** A library folder with its display stats, resolved off the main thread. */
data class LibraryFolder(
    val name: String,
    val scanCount: Int,
    val digitizedCount: Int,
    val coverUri: String?,
    val lastActivityAt: Long,
)
