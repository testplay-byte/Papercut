package com.papercut.app.core.data

import android.os.Environment
import java.io.ByteArrayOutputStream

/**
 * Minimal PDF 1.4 writer — pages are full-bleed JPEGs (image-only scanner PDF,
 * like most scanning apps produce). No external dependencies: just bytes and
 * a hand-built xref table with exact per-object offsets.
 *
 * Object layout: 1 catalog, 2 page-tree, then for page i (0-based):
 * page = 3+3i, content stream = 4+3i, image xobject = 5+3i.
 */
object PdfExporter {

    /** One pre-encoded page: JPEG bytes + pixel dimensions. */
    data class EncodedPage(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int)

    /** Encode a bitmap as an [EncodedPage] without keeping the bitmap alive. */
    fun encodePage(bmp: android.graphics.Bitmap, maxSide: Int = 2000): EncodedPage {
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest <= maxSide) bmp
        else android.graphics.Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * maxSide / longest).coerceAtLeast(1),
            (bmp.height * maxSide / longest).coerceAtLeast(1),
            true,
        )
        val w = scaled.width
        val h = scaled.height
        val out = java.io.ByteArrayOutputStream(512 * 1024)
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bmp) scaled.recycle()
        return EncodedPage(out.toByteArray(), w, h)
    }

    /**
     * Build an in-memory PDF from PRE-ENCODED pages (JPEG bytes). Callers
     * encode one page at a time and recycle the bitmap immediately — holding
     * every page as a bitmap (≈6 MB each) OOMs on long documents.
     */
    fun build(encoded: List<EncodedPage>): ByteArray {
        require(encoded.isNotEmpty()) { "PDF needs at least one page" }

        val out = ByteArrayOutputStream(2 * 1024 * 1024)
        val offsets = HashMap<Int, Int>() // object id -> byte offset

        fun writeString(s: String) = out.write(s.toByteArray())
        fun beginObj(id: Int) { offsets[id] = out.size(); writeString("$id 0 obj\n") }
        fun endObj() = writeString("endobj\n")

        writeString("%PDF-1.4\n%\n")

        // kids list for the page tree
        val kids = encoded.indices.joinToString(" ") { "${3 + it * 3} 0 R" }

        // catalog + page tree (ids 1,2) first so /Root resolves
        beginObj(1); writeString("<< /Type /Catalog /Pages 2 0 R >>\n"); endObj()
        beginObj(2); writeString("<< /Type /Pages /Count ${encoded.size} /Kids [$kids] >>\n"); endObj()

        encoded.forEachIndexed { i, e ->
            val base = 3 + i * 3
            val (mw, mh) = mediaBox(e.widthPx, e.heightPx)

            beginObj(base)
            writeString(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $mw $mh] " +
                    "/Resources << /ProcSet [/PDF /ImageC] /XObject << /Im$base ${base + 2} 0 R >> >> " +
                    "/Contents ${base + 1} 0 R >>\n",
            )
            endObj()

            val content = "q\n$mw 0 0 $mh 0 0 cm\n/Im$base Do\nQ".toByteArray()
            beginObj(base + 1)
            writeString("<< /Length ${content.size} >>\nstream\n")
            out.write(content)
            writeString("\nendstream\n")
            endObj()

            beginObj(base + 2)
            writeString(
                "<< /Type /XObject /Subtype /Image /Width ${e.widthPx} /Height ${e.heightPx} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${e.jpeg.size} >>\nstream\n",
            )
            out.write(e.jpeg)
            writeString("\nendstream\n")
            endObj()
        }

        // xref: every id 0..maxId in order
        val maxId = 2 + encoded.size * 3
        val xrefPos = out.size()
        writeString("xref\n0 ${maxId + 1}\n")
        writeString("0000000000 65535 f \n")
        for (id in 1..maxId) {
            val off = offsets[id] ?: error("missing object $id")
            writeString(String.format(java.util.Locale.US, "%010d 00000 n \n", off))
        }
        writeString("trailer\n<< /Size ${maxId + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")

        return out.toByteArray()
    }

    private fun mediaBox(pxW: Int, pxH: Int): Pair<Int, Int> {
        val ar = pxW.toFloat() / pxH.coerceAtLeast(1)
        return if (ar >= 1f) 842 to (842f / ar).toInt().coerceAtLeast(1)
        else (842f * ar).toInt().coerceAtLeast(1) to 842
    }

    /**
     * Save the built PDF where users find it: MediaStore Downloads on API 29+,
     * app external Downloads (FileProvider uri) on 26-28. Null on failure.
     */
    fun saveToDownloads(context: android.content.Context, bytes: ByteArray, title: String): android.net.Uri? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, "$title.pdf")
            put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
            put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Papercut")
            put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        // API 29+: MediaStore Downloads (no permission needed, visible in Files).
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
        // API 26-28: app-specific external Downloads (always writable, no perm,
        // surfaces in Files apps). Sharing is via FileProvider in the export VM.
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val f = java.io.File(dir, "$title.pdf")
            f.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Write bytes to a cache file and return a grantable FileProvider uri (for ACTION_SEND). */
    fun cacheForShare(context: android.content.Context, bytes: ByteArray, name: String): android.net.Uri? = try {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val f = java.io.File(dir, "$name.pdf")
        f.writeBytes(bytes)
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    } catch (_: Exception) {
        null
    }
}
