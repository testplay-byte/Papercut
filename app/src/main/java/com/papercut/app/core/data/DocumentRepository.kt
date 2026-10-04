package com.papercut.app.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.papercut.app.core.data.model.DocumentMeta
import com.papercut.app.core.data.model.PageEdits
import com.papercut.app.core.data.model.DocumentSummary
import com.papercut.app.core.data.model.PageSpec
import com.papercut.app.core.data.model.PageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter

/**
 * Document-centric storage (v2). Layout inside the user-picked root:
 *
 *   Papercut/<Folder>/<Doc Name>/
 *     meta.json         DocumentMeta (name, pages[], per-page crop/rotation/filter)
 *     page-01.jpg       original capture — edits are non-destructive
 *     page-01.html      current AI digitization
 *     page-01.prev.html single AI backup
 *
 * Legacy scans (IMG_x.jpg / IMG_ximprove.html straight in a folder) surface as
 * 1-page documents via [listDocuments]; editing one upgrades it into the new
 * layout lazily on first write ([migrateLegacyIfNeeded]).
 */
class DocumentRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    companion object {
        const val ROOT_DIR_NAME = "Papercut"
        const val META_FILE = "meta.json"
        const val DEFAULT_FOLDER = "Default"
        fun pageFile(i: Int) = "page-%02d.jpg".format(i)
        fun htmlFile(i: Int) = "page-%02d.html".format(i)
        fun htmlBackupFile(i: Int) = "page-%02d.prev.html".format(i)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Serializes every meta.json read-modify-write across the app. */
    private val metaLock = kotlinx.coroutines.sync.Mutex()

    private val rootDir: DocumentFile?
        get() {
            val uri = settings.settings.value.rootFolderUri ?: return null
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
            return tree.findFile(ROOT_DIR_NAME) ?: tree.createDirectory(ROOT_DIR_NAME)
        }

    private fun folderDir(name: String, create: Boolean = false): DocumentFile? =
        rootDir?.let { it.findFile(name) ?: if (create) it.createDirectory(name) else null }

    suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        val root = rootDir ?: return@withContext false
        if (root.findFile(DEFAULT_FOLDER) == null) root.createDirectory(DEFAULT_FOLDER) != null else true
    }

    // ---------------- folders ----------------

    data class FolderInfo(val name: String, val docCount: Int, val coverUri: String?)

    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        val root = rootDir ?: return@withContext emptyList()
        root.listFiles().filter { it.isDirectory }.map { folder ->
            val docs = folder.listFiles().filter { it.isDirectory &&
                it.findFile(META_FILE)?.exists() == true }
            val legacy = legacyScansIn(folder)
            val cover = docs.firstOrNull()?.let { d ->
                d.listFiles().firstOrNull { it.name?.endsWith(".jpg") == true }?.uri?.toString()
            } ?: legacy.firstOrNull()?.second?.uri?.toString()
            FolderInfo(folder.name ?: "", docs.size + legacy.size, cover)
        }.sortedWith(compareByDescending<FolderInfo> { it.name == DEFAULT_FOLDER }.thenBy { it.name })
    }

    suspend fun createFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        name.isNotBlank() && rootDir?.findFile(name) == null && folderDir(name, create = true) != null
    }

    suspend fun renameFolder(old: String, new: String): Boolean = withContext(Dispatchers.IO) {
        if (old == DEFAULT_FOLDER || new.isBlank()) return@withContext false
        folderDir(old)?.renameTo(new) ?: false
    }

    suspend fun deleteFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        if (name == DEFAULT_FOLDER) return@withContext false
        folderDir(name)?.delete() ?: false
    }

    /** legacy IMG_*.jpg directly inside a folder (excluding ones inside doc dirs) */
    private fun legacyScansIn(folder: DocumentFile): List<Pair<String, DocumentFile>> =
        folder.listFiles().filter {
            it.isFile && it.name?.startsWith("IMG_") == true && it.name?.endsWith(".jpg") == true
        }.map { (it.name ?: "").removeSuffix(".jpg") to it }

    // ---------------- documents ----------------

    suspend fun listDocuments(folderName: String): List<DocumentSummary> = withContext(Dispatchers.IO) {
        val folder = folderDir(folderName) ?: return@withContext emptyList()

        val modern = folder.listFiles().filter { dir ->
            dir.isDirectory && dir.findFile(META_FILE)?.exists() == true
        }.mapNotNull { dir ->
            val meta = readMeta(dir) ?: return@mapNotNull null
            val files = dir.listFiles().associateBy { it.name }
            DocumentSummary(
                folder = folderName,
                name = meta.name,
                pageCount = meta.pages.size,
                digitizedPages = meta.pages.count { files[htmlFile(it.index)] != null },
                coverUri = files[pageFile(meta.pages.firstOrNull()?.index ?: 1)]?.uri?.toString(),
                lastActivityAt = dir.lastModified(),
                isLegacy = false,
            )
        }

        val legacy = legacyScansIn(folder).map { (base, img) ->
            DocumentSummary(
                folder = folderName,
                name = base,
                pageCount = 1,
                digitizedPages = if (folder.findFile("${base}improve.html") != null) 1 else 0,
                coverUri = img.uri.toString(),
                lastActivityAt = img.lastModified(),
                isLegacy = true,
            )
        }

        (modern + legacy).sortedByDescending { it.lastActivityAt }
    }

    suspend fun getPages(doc: DocumentSummary): List<PageView> = withContext(Dispatchers.IO) {
        if (doc.isLegacy) {
            val folder = folderDir(doc.folder) ?: return@withContext emptyList()
            val img = folder.findFile("${doc.name}.jpg") ?: return@withContext emptyList()
            listOf(PageView(
                folder = doc.folder, docName = doc.name,
                spec = PageSpec(1, "${doc.name}.jpg"),
                imageUri = img.uri.toString(),
                htmlUri = folder.findFile("${doc.name}improve.html")?.uri?.toString(),
                backupUri = folder.findFile("${doc.name}improve_old.html")?.uri?.toString(),
            ))
        } else {
            val dir = folderDir(doc.folder)?.findFile(doc.name)?.let { DocumentFile.fromTreeUri(context, it.uri) }
                ?: return@withContext emptyList()
            val meta = readMeta(dir) ?: return@withContext emptyList()
            val files = dir.listFiles().associateBy { it.name }
            meta.pages.map { p ->
                PageView(
                    folder = doc.folder, docName = doc.name, spec = p,
                    imageUri = files[p.fileName]?.uri?.toString() ?: "",
                    htmlUri = files[htmlFile(p.index)]?.uri?.toString(),
                    backupUri = files[htmlBackupFile(p.index)]?.uri?.toString(),
                )
            }.filter { it.imageUri.isNotEmpty() }
        }
    }

    /** Read-modify-write meta.json under the meta lock. Legacy docs migrate first. */
    suspend fun updateMeta(folder: String, docName: String, transform: (DocumentMeta) -> DocumentMeta): Boolean =
        withContext(Dispatchers.IO) {
            if (!migrateLegacyIfNeeded(folder, docName)) return@withContext false
            metaLock.lock()
            try {
                val dir = documentDir(folder, docName, create = false) ?: return@withContext false
                val meta = readMeta(dir) ?: return@withContext false
                writeMeta(dir, transform(meta))
            } finally {
                metaLock.unlock()
            }
        }

    /** Ensure doc dir + meta exist; migrates a legacy single scan in place. */
    suspend fun migrateLegacyIfNeeded(folder: String, docName: String): Boolean = withContext(Dispatchers.IO) {
        val fDir = folderDir(folder) ?: return@withContext false
        val existing = fDir.findFile(docName)
        // only treat as migrated when the dir actually HAS meta — a half-finished
        // migration (dir without meta) re-enters and heals below
        if (existing?.isDirectory == true && existing.findFile(META_FILE)?.exists() == true)
            return@withContext true
        val img = fDir.findFile("$docName.jpg")
        val dir = if (existing?.isDirectory == true) existing else fDir.createDirectory(docName)
            ?: return@withContext false
        val alreadyHavePage = dir.findFile(pageFile(1))?.exists() == true
        if (!alreadyHavePage) {
            img ?: return@withContext false
            val newImg = dir.createFile("image/jpeg", pageFile(1)) ?: return@withContext false
            // verify the copy actually landed BEFORE deleting the original
            // providers that dedupe createFile store "page-01 (1).jpg" — meta
            // would then point at a file that does not exist
            if (newImg.name != pageFile(1)) { newImg.delete(); return@withContext false }
            val copied = try {
                val ok = context.contentResolver.openInputStream(img.uri)?.use { input ->
                    context.contentResolver.openOutputStream(newImg.uri)?.use { output -> input.copyTo(output) }
                    true
                } ?: false
                ok && newImg.name == pageFile(1) && newImg.length() > 0
            } catch (_: Exception) {
                false
            }
            if (!copied) {
                newImg.delete()
                return@withContext false // original stays put — fully recoverable
            }
        }
        val htmlOld = fDir.findFile("${docName}improve.html")
        if (htmlOld != null && dir.findFile(htmlFile(1)) == null) {
            dir.createFile("text/html", htmlFile(1))?.let { nh ->
                try {
                    context.contentResolver.openInputStream(htmlOld.uri)?.use { i ->
                        context.contentResolver.openOutputStream(nh.uri)?.use { o -> i.copyTo(o) }
                    }
                } catch (_: Exception) {
                }
            }
        }
        val oldHtml = fDir.findFile("${docName}improve_old.html")
        if (oldHtml != null && dir.findFile(htmlBackupFile(1)) == null) {
            dir.createFile("text/html", htmlBackupFile(1))?.let { nh ->
                try {
                    context.contentResolver.openInputStream(oldHtml.uri)?.use { i ->
                        context.contentResolver.openOutputStream(nh.uri)?.use { o -> i.copyTo(o) }
                    }
                } catch (_: Exception) {
                }
            }
        }
        // never overwrite a multi-page meta: a directory holding page-01..09
        // whose meta was lost would otherwise be rewritten as one page
        val metaWritten = if (dir.findFile(META_FILE)?.exists() == true) true else
            writeMeta(dir, DocumentMeta(docName, System.currentTimeMillis(),
                listOf(PageSpec(1, pageFile(1), filter = com.papercut.app.core.data.model.PageFilter.ORIGINAL))))
        if (!metaWritten) return@withContext false // originals kept — retryable
        img?.delete()
        htmlOld?.delete()
        oldHtml?.delete()
        true
    }

    // ---------------- creation & pages ----------------

    /** Create a new document with initial captured bitmaps. Returns folder/doc names. */
    suspend fun createDocument(
        folderName: String,
        bits: List<Bitmap>,
        name: String? = null,
        edits: List<PageEdits> = emptyList(),
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val folder = folderDir(folderName, create = true) ?: return@withContext null
            val docName = sanitizeName(name ?: defaultDocName())
            var finalName = docName
            var n = 1
            // never clobber a dir OR a legacy scan file of the same stem
            while (folder.findFile(finalName) != null || folder.findFile("$finalName.jpg") != null) {
                finalName = "$docName ($n)"
                n++
            }
            val docDir = folder.createDirectory(finalName) ?: return@withContext null
            val pages = ArrayList<PageSpec>()
            bits.forEachIndexed { i, bmp ->
                val file = docDir.createFile("image/jpeg", pageFile(i + 1))
                if (file != null) {
                    try {
                        context.contentResolver.openOutputStream(file.uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                        val e = edits.getOrNull(i) ?: PageEdits()
                        pages += PageSpec(i + 1, pageFile(i + 1), e.quad, e.rotation, e.filter, e.aiMode)
                    } catch (_: Exception) {
                        file.delete()
                    }
                }
            }
            if (pages.isEmpty()) { docDir.delete(); return@withContext null }
            if (!writeMeta(docDir, DocumentMeta(finalName, System.currentTimeMillis(), pages))) {
                docDir.delete(); return@withContext null
            }
            folderName to finalName
        }

    /** Append captured pages to an existing document (legacy migrates first). */
    suspend fun addPages(
        folder: String,
        docName: String,
        bits: List<Bitmap>,
        edits: List<PageEdits> = emptyList(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!migrateLegacyIfNeeded(folder, docName)) return@withContext false
            metaLock.lock()
            try {
            val dir = documentDir(folder, docName) ?: return@withContext false
            val meta = readMeta(dir) ?: return@withContext false
            var next = meta.pages.maxOfOrNull { it.index } ?: 0
            val added = ArrayList(meta.pages)
            var ok = false
            var appended = 0
            bits.forEach { bmp ->
                next++
                val file = dir.createFile("image/jpeg", pageFile(next)) ?: return@forEach
                try {
                    context.contentResolver.openOutputStream(file.uri)?.use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }
                    val e = edits.getOrNull(appended) ?: PageEdits()
                    added += PageSpec(next, pageFile(next), e.quad, e.rotation, e.filter, e.aiMode)
                    appended++
                    ok = true
                } catch (_: Exception) {
                    file.delete()
                }
            }
            ok && writeMeta(dir, meta.copy(pages = renumber(added)))
            } finally {
                metaLock.unlock()
            }
        }

    private fun renumber(pages: List<PageSpec>): List<PageSpec> =
        pages.mapIndexed { i, p -> p.copy(index = i + 1) }

    /**
     * Reorder pages by rewriting meta AND renaming every artifact (photo,
     * twin, backup) into its new slot — photos alone would orphan twins.
     *
     * Safety model:
     *  - temp names are SELF-DESCRIBING (`tmp-<originalName>`), so an
     *    interrupted rename can be reversed without guessing
     *  - every rename in BOTH passes is checked; any failure rolls the whole
     *    move back (pass 2 first, then pass 1)
     *  - leftovers from a crash are RESTORED on the next open, never deleted
     */
    suspend fun setOrder(folder: String, docName: String, orderedIndexes: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            metaLock.lock()
            try {
                val dir = documentDir(folder, docName) ?: return@withContext false
                val meta = readMeta(dir) ?: return@withContext false
                val byIdx = meta.pages.associateBy { it.index }
                val specs = orderedIndexes.mapNotNull { byIdx[it] }
                if (specs.size != meta.pages.size) return@withContext false
                restoreTmp(dir)

                // moved: currentName -> originalName (reverse map for rollback)
                val moved = ArrayList<Pair<String, String>>()
                fun rename(from: String, to: String): Boolean {
                    val f = dir.findFile(from) ?: return false
                    if (!f.renameTo(to)) return false
                    moved += to to from
                    return true
                }
                // pass 1 — park everything under self-describing temp names
                for (p in specs) {
                    if (!rename(p.fileName, tmpOf(p.fileName))) return@withContext rollback(dir, moved)
                    dir.findFile(htmlFile(p.index))?.let {
                        if (!rename(it.name!!, tmpOf(htmlFile(p.index)))) return@withContext rollback(dir, moved)
                    }
                    dir.findFile(htmlBackupFile(p.index))?.let {
                        if (!rename(it.name!!, tmpOf(htmlBackupFile(p.index)))) return@withContext rollback(dir, moved)
                    }
                }
                // pass 2 — temps into final 1-based slots (all vacated by pass 1)
                val newPages = ArrayList<PageSpec>()
                for ((i, p) in specs.withIndex()) {
                    val slot = pageFile(i + 1)
                    if (!rename(tmpOf(p.fileName), slot)) return@withContext rollback(dir, moved)
                    dir.findFile(tmpOf(htmlFile(p.index)))?.let {
                        rename(it.name!!, htmlFile(i + 1))
                    }
                    dir.findFile(tmpOf(htmlBackupFile(p.index)))?.let {
                        rename(it.name!!, htmlBackupFile(i + 1))
                    }
                    newPages += p.copy(index = i + 1, fileName = slot)
                }
                writeMeta(dir, meta.copy(pages = newPages))
            } finally {
                metaLock.unlock()
            }
        }

    /** Self-describing temp name: tmp-<original> so a crash is reversible. */
    private fun tmpOf(original: String) = "tmp-$original"

    private fun Boolean?.orFalse() = this == true

    /**
     * Restore artifacts left as tmp-* by an interrupted rename. These files hold
     * UNTOUCHED originals — renaming them back is safe. `tmp-twin.html` is the
     * twin scratch file and is the only one we delete.
     */
    private fun restoreTmp(dir: DocumentFile) {
        dir.listFiles().filter { it.isFile && it.name?.startsWith("tmp-") == true }.forEach { f ->
            val n = f.name ?: return@forEach
            if (n == "tmp-twin.html") { f.delete(); return@forEach }
            val original = n.removePrefix("tmp-")
            if (!f.renameTo(original)) f.delete() // only now is deleting safe
        }
    }

    /** Undo a failed reorder (reverse order so targets are free again). */
    private fun rollback(dir: DocumentFile, moved: List<Pair<String, String>>): Boolean {
        moved.asReversed().forEach { (current, original) ->
            dir.findFile(current)?.renameTo(original) ?: dir.findFile(original)
        }
        restoreTmp(dir)
        return false
    }

    suspend fun deletePage(folder: String, docName: String, pageIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            metaLock.lock()
            try {
                val dir = documentDir(folder, docName) ?: return@withContext false
                val meta = readMeta(dir) ?: return@withContext false
                val doomed = meta.pages.find { it.index == pageIndex } ?: return@withContext false
                dir.findFile(doomed.fileName)?.delete()
                dir.findFile(htmlFile(pageIndex))?.delete()
                dir.findFile(htmlBackupFile(pageIndex))?.delete()
                // shift each remaining page's photo + twin + backup into its new slot
                val remaining = meta.pages.filter { it.index != pageIndex }.sortedBy { it.index }
                for ((i, p) in remaining.withIndex()) {
                    val newSlot = i + 1
                    if (p.index != newSlot) {
                        dir.findFile(p.fileName)?.renameTo(pageFile(newSlot))
                        dir.findFile(htmlFile(p.index))?.renameTo(htmlFile(newSlot))
                        dir.findFile(htmlBackupFile(p.index))?.renameTo(htmlBackupFile(newSlot))
                    }
                }
                writeMeta(dir, meta.copy(
                    pages = remaining.mapIndexed { i, p -> p.copy(index = i + 1, fileName = pageFile(i + 1)) },
                ))
            } finally {
                metaLock.unlock()
            }
        }

    suspend fun deleteDocument(doc: DocumentSummary): Boolean = withContext(Dispatchers.IO) {
        metaLock.lock()
        try {
            val folder = folderDir(doc.folder) ?: return@withContext false
            if (doc.isLegacy) {
                var ok = folder.findFile("${doc.name}.jpg")?.delete() == true
                folder.findFile("${doc.name}improve.html")?.delete()
                folder.findFile("${doc.name}improve_old.html")?.delete()
                ok
            } else {
                // delete() fails on a non-empty dir on several SAF providers
                val dir = folder.findFile(doc.name)
                if (dir?.isDirectory == true) dir.listFiles().forEach { it.delete() }
                dir?.delete() ?: false
            }
        } finally {
            metaLock.unlock()
        }
    }

    suspend fun renameDocument(folder: String, old: String, new: String): Boolean = withContext(Dispatchers.IO) {
        val fDir = folderDir(folder) ?: return@withContext false
        val safe = sanitizeName(new)
        if (safe.isBlank() || safe == old || fDir.findFile(safe) != null) return@withContext false
        val doc = fDir.findFile(old)?.takeIf { it.isDirectory } ?: return@withContext false
        metaLock.lock()
        try {
            if (!doc.renameTo(safe)) return@withContext false
            val d = documentDir(folder, safe)
            val metaOk = d?.let { readMeta(it)?.let { m -> writeMeta(it, m.copy(name = safe)) } } == true
            if (!metaOk) {
                d?.renameTo(old) // never leave dir name and meta.name disagreeing
                return@withContext false
            }
            true
        } finally {
            metaLock.unlock()
        }
    }

    // ---------------- AI results per page ----------------

    /**
     * Write AI html for a page, demoting current to backup. Works for legacy
     * via migration. Resolves the slot by PHOTO IDENTITY (file name), never by
     * position — a twin landing on someone else's page is unrecoverable.
     */
    suspend fun savePageHtml(page: PageView, html: String): Boolean = withContext(Dispatchers.IO) {
        if (!migrateLegacyIfNeeded(page.folder, page.docName)) return@withContext false
        metaLock.lock()
        try {
            val dir = documentDir(page.folder, page.docName) ?: return@withContext false
            val meta = readMeta(dir) ?: return@withContext false
            // identity ONLY: a page deleted mid-flight must drop the paid result,
            // never write it onto whoever shifted into that slot
            val target = meta.pages.find { it.fileName == page.spec.fileName }
                ?: return@withContext false
            val slot = target.index
            // 1. write the new twin to a temp file (createFile with a TAKEN name
            //    dedupes on SAF, which would orphan the old twin's slot)
            val tmp = dir.createFile("text/html", "tmp-twin.html") ?: return@withContext false
            val written = try {
                context.contentResolver.openOutputStream(tmp.uri)?.use { stream ->
                    OutputStreamWriter(stream).use { it.write(html) }
                }
                true
            } catch (_: Exception) {
                false
            }
            if (!written) { tmp.delete(); return@withContext false }
            // 2. only now demote the previous twin, then swap the new one in
            dir.findFile(htmlBackupFile(slot))?.delete()
            dir.findFile(htmlFile(slot))?.let { cur ->
                if (!cur.renameTo(htmlBackupFile(slot))) cur.delete()
            }
            val swapped = tmp.renameTo(htmlFile(slot))
            if (!swapped) tmp.delete()
            swapped
        } finally {
            metaLock.unlock()
        }
    }

    suspend fun restorePageBackup(folder: String, docName: String, pageIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            metaLock.lock()
            try {
                val dir = documentDir(folder, docName) ?: return@withContext false
                dir.findFile(htmlFile(pageIndex))?.delete()
                dir.findFile(htmlBackupFile(pageIndex))?.renameTo(htmlFile(pageIndex)) ?: false
            } finally {
                metaLock.unlock()
            }
        }

    suspend fun discardPageBackup(folder: String, docName: String, pageIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            metaLock.lock()
            try {
                documentDir(folder, docName)?.findFile(htmlBackupFile(pageIndex))?.delete() ?: false
            } finally {
                metaLock.unlock()
            }
        }

    suspend fun readPageHtml(page: PageView, backup: Boolean): String? = withContext(Dispatchers.IO) {
        val dir = documentDir(page.folder, page.docName) ?: return@withContext null
        val name = if (backup) htmlBackupFile(page.spec.index) else htmlFile(page.spec.index)
        val file = dir.findFile(name) ?: return@withContext null
        try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- helpers ----------------

    private fun documentDir(folder: String, docName: String, create: Boolean = false): DocumentFile? {
        val f = folderDir(folder) ?: return null
        return f.findFile(docName) ?: if (create) f.createDirectory(docName) else null
    }

    private fun readMeta(dir: DocumentFile): DocumentMeta? {
        val file = dir.findFile(META_FILE) ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                json.decodeFromString<DocumentMeta>(it.readText())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(dir: DocumentFile, meta: DocumentMeta): Boolean {
        // Write IN PLACE (truncating stream). delete-then-create left a window
        // where a crash erased meta.json entirely — the document vanished from
        // the library with every page still on disk.
        val payload = try {
            json.encodeToString(DocumentMeta.serializer(), meta)
        } catch (_: Exception) {
            return false
        }
        return try {
            val existing = dir.findFile(META_FILE)
            if (existing != null) {
                // "rwt" is the documented truncating mode; verify the bytes landed
                // so a torn write can't blank a whole document out of the library
                context.contentResolver.openOutputStream(existing.uri, "rwt")?.use { stream ->
                    OutputStreamWriter(stream).use {
                        it.write(payload)
                        it.flush()
                    }
                } != null && existing.length() == payload.toByteArray().size.toLong()
            } else {
                val file = dir.createFile("application/json", META_FILE) ?: return false
                context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                    OutputStreamWriter(stream).use {
                        it.write(payload)
                        it.flush()
                    }
                } != null && file.length() > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Suggested name for a new capture session ("Scan 2026-10-04 14:32"). */
    fun suggestName(): String = defaultDocName()

    private fun defaultDocName(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        return "Scan $ts"
    }

    private fun sanitizeName(raw: String): String =
        raw.trim().replace(Regex("[/\\\\:*?\"<>|#\\u0000-\\u001F]"), "_").take(80).ifBlank { "Scan" }
}
