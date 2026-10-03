package com.papercut.app.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.papercut.app.core.data.model.LibraryFolder
import com.papercut.app.core.data.model.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All document storage behind one repository, every operation on Dispatchers.IO
 * (the old app hit the disk on the main thread in 3 screens -> jank/ANR).
 *
 * On-disk layout inside the user-picked root (SAF tree):
 *   Papercut/
 *     <Folder>/
 *       IMG_<timestamp>.jpg            original capture
 *       IMG_<timestamp>.html           current digitized version
 *       IMG_<timestamp>.prev.html      previous version (single backup)
 *
 * Naming uses ".html"/".prev.html" suffixes instead of the old "improve.html"
 * string-mangling — the base name is the stable id for a scan.
 */
class ScanRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    companion object {
        const val ROOT_DIR_NAME = "Papercut"
        const val BACKUP_DIR_NAME = "Default" // first, undeletable folder
        fun htmlName(base: String) = "$base.html"
        fun backupName(base: String) = "$base.prev.html"
        fun imageName(base: String) = "$base.jpg"
    }

    private val rootDir: DocumentFile?
        get() {
            val uri = settings.settings.value.rootFolderUri ?: return null
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
            var root = tree.findFile(ROOT_DIR_NAME)
            if (root == null || !root.isDirectory) root = tree.createDirectory(ROOT_DIR_NAME)
            return root
        }

    /** Resolve the root; create the structure on first use. */
    suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        val root = rootDir ?: return@withContext false
        if (root.findFile(BACKUP_DIR_NAME) == null) root.createDirectory(BACKUP_DIR_NAME) != null else true
    }

    suspend fun listFolders(): List<LibraryFolder> = withContext(Dispatchers.IO) {
        val root = rootDir ?: return@withContext emptyList()
        root.listFiles()
            .filter { it.isDirectory }
            .map { folder ->
                val files = folder.listFiles()
                val images = files.filter { it.name?.endsWith(".jpg") == true }
                    .sortedByDescending { it.lastModified() }
                LibraryFolder(
                    name = folder.name ?: "",
                    scanCount = images.size,
                    digitizedCount = files.count { it.name?.endsWith(".html") == true && it.name?.endsWith(".prev.html") != true },
                    coverUri = images.firstOrNull()?.uri?.toString(),
                    lastActivityAt = images.firstOrNull()?.lastModified() ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<LibraryFolder> { it.name == BACKUP_DIR_NAME }
                    .thenByDescending { it.lastActivityAt }
            )
    }

    private fun folderDir(name: String, create: Boolean = false): DocumentFile? {
        val root = rootDir ?: return null
        return root.findFile(name) ?: if (create) root.createDirectory(name) else null
    }

    suspend fun createFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        if (name.isBlank() || rootDir?.findFile(name) != null) false
        else folderDir(name, create = true) != null
    }

    suspend fun renameFolder(old: String, new: String): Boolean = withContext(Dispatchers.IO) {
        if (old == BACKUP_DIR_NAME || new.isBlank()) return@withContext false
        val folder = folderDir(old) ?: return@withContext false
        folder.renameTo(new)
    }

    /** Delete folder and its scans. Refuses the undeletable Default folder. */
    suspend fun deleteFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        if (name == BACKUP_DIR_NAME) return@withContext false
        folderDir(name)?.delete() ?: false
    }

    suspend fun listScans(folderName: String): List<ScanItem> = withContext(Dispatchers.IO) {
        val folder = folderDir(folderName) ?: return@withContext emptyList()
        val files = folder.listFiles().associateBy { it.name }
        files.values
            .filter { it.name?.endsWith(".jpg") == true }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { img ->
                val base = img.name?.removeSuffix(".jpg") ?: return@mapNotNull null
                ScanItem(
                    folder = folderName,
                    name = base,
                    imageUri = img.uri.toString(),
                    htmlUri = files[htmlName(base)]?.uri?.toString(),
                    backupUri = files[backupName(base)]?.uri?.toString(),
                )
            }
    }

    suspend fun saveCapture(folderName: String, bitmap: Bitmap): ScanItem? =
        withContext(Dispatchers.IO) {
            val folder = folderDir(folderName, create = true) ?: return@withContext null
            val base = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            if (folder.listFiles().any { it.name == imageName(base) }) {
                // same-second collision guard
                return@withContext null
            }
            val file = folder.createFile("image/jpeg", imageName(base)) ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(file.uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                } ?: return@withContext null
            } catch (_: Exception) {
                file.delete()
                return@withContext null
            }
            ScanItem(folderName, base, file.uri.toString(), htmlUri = null, backupUri = null)
        }

    /**
     * Write AI output as the current version, demoting the previous current to
     * ".prev.html" atomically-enough for our restore flow.
     * Returns false if nothing could be written (folder gone, SAF denied).
     */
    suspend fun saveHtml(item: ScanItem, html: String): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDir(item.folder) ?: return@withContext false
        // demote existing current -> backup (replacing any older backup)
        folder.findFile(backupName(item.name))?.delete()
        folder.findFile(htmlName(item.name))?.renameTo(backupName(item.name))

        val file = folder.createFile("text/html", htmlName(item.name)) ?: return@withContext false
        try {
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                OutputStreamWriter(stream).use { it.write(html) }
            } ?: run { file.delete(); return@withContext false }
            true
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    /**
     * Keep the backup as current (restore). Used by the viewer's Restore action.
     */
    suspend fun restoreBackup(item: ScanItem): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDir(item.folder) ?: return@withContext false
        val backup = folder.findFile(backupName(item.name)) ?: return@withContext false
        folder.findFile(htmlName(item.name))?.delete()
        backup.renameTo(htmlName(item.name))
    }

    /** Throw away the backup (keep current). */
    suspend fun discardBackup(item: ScanItem): Boolean = withContext(Dispatchers.IO) {
        folderDir(item.folder)?.findFile(backupName(item.name))?.delete() ?: false
    }

    suspend fun readHtml(item: ScanItem, backup: Boolean = false): String? = withContext(Dispatchers.IO) {
        val folder = folderDir(item.folder) ?: return@withContext null
        val file = folder.findFile(if (backup) backupName(item.name) else htmlName(item.name))
            ?: return@withContext null
        try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteScan(item: ScanItem): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDir(item.folder) ?: return@withContext false
        var ok = false
        folder.findFile(imageName(item.name))?.let { ok = it.delete() }
        folder.findFile(htmlName(item.name))?.delete()
        folder.findFile(backupName(item.name))?.delete()
        ok
    }
}
