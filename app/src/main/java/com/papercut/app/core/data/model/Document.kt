package com.papercut.app.core.data.model

import kotlinx.serialization.Serializable

// ScanMode lives in Models.kt (same package).

/** Image treatment applied to a page. Non-destructive: stored, never baked. */
@Serializable
enum class PageFilter { ORIGINAL, MAGIC, GRAYSCALE, BLACK_WHITE }

/**
 * The four corners of the document inside the page image, in NORMALIZED
 * coordinates (0..1), ordered TL, TR, BR, BL. null = full page (no crop).
 */
@Serializable
data class Quad(val tlX: Float, val tlY: Float, val trX: Float, val trY: Float,
                val brX: Float, val brY: Float, val blX: Float, val blY: Float) {
    /** Pixel-space [TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy] for the warp math. */
    fun toPixelArray(imgW: Int, imgH: Int): FloatArray = floatArrayOf(
        tlX * imgW, tlY * imgH, trX * imgW, trY * imgH,
        brX * imgW, brY * imgH, blX * imgW, blY * imgH,
    )

    /** Normalized corners TL,TR,BR,BL as (x,y) pairs. */
    fun corners(): List<Pair<Float, Float>> =
        listOf(tlX to tlY, trX to trY, brX to brY, blX to blY)

    /** Replace corner [index] (0=TL..3=BL) with a new normalized point. */
    fun withCorner(index: Int, x: Float, y: Float): Quad = when (index) {
        0 -> copy(tlX = x, tlY = y)
        1 -> copy(trX = x, trY = y)
        2 -> copy(brX = x, brY = y)
        else -> copy(blX = x, blY = y)
    }

    /** Rotate the crop region 90° CW together with the page image. */
    fun rotated90(): Quad {
        fun r(x: Float, y: Float) = (1f - y) to x // normalized 90° CW
        val ntl = r(blX, blY); val ntr = r(tlX, tlY)
        val nbr = r(trX, trY); val nbl = r(brX, brY)
        return Quad(ntl.first, ntl.second, ntr.first, ntr.second, nbr.first, nbr.second, nbl.first, nbl.second)
    }

    val isFullPage: Boolean
        get() = tlX == 0f && tlY == 0f && trX == 1f && trY == 0f &&
            brX == 1f && brY == 1f && blX == 0f && blY == 1f

    companion object {
        val FULL = Quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        fun fromPixels(p: FloatArray, imgW: Int, imgH: Int) = Quad(
            tlX = p[0] / imgW, tlY = p[1] / imgH,
            trX = p[2] / imgW, trY = p[3] / imgH,
            brX = p[4] / imgW, brY = p[5] / imgH,
            blX = p[6] / imgW, blY = p[7] / imgH,
        )
    }
}

/** Everything editable about one page. */
@Serializable
data class PageSpec(
    val index: Int,               // 1-based order in the document
    val fileName: String,         // original capture, e.g. "page-01.jpg"
    val quad: Quad? = null,       // perspective crop corners
    val rotation: Int = 0,        // 0/90/180/270 extra rotation (on top of EXIF-upright)
    val filter: PageFilter = PageFilter.MAGIC,
    val aiMode: ScanMode = ScanMode.TEXT,   // digitize mode used for this page
)

/** Per-page edits carried from a capture draft into the saved document. */
data class PageEdits(
    val quad: Quad? = null,
    val rotation: Int = 0,
    val filter: PageFilter = PageFilter.MAGIC,
    val aiMode: ScanMode = ScanMode.TEXT,
)

/** One scanned document: metadata + ordered pages. Persisted as meta.json. */
@Serializable
data class DocumentMeta(
    val name: String,
    val createdAt: Long,
    val pages: List<PageSpec>,
)

/** View-model of a document for lists/grids (stats resolved off-thread). */
data class DocumentSummary(
    val folder: String,
    val name: String,
    val pageCount: Int,
    val digitizedPages: Int,
    val coverUri: String?,
    val lastActivityAt: Long,
    val isLegacy: Boolean,        // old per-photo scan bridged as 1-page doc
)

/** One page with its resolved file uris for the UI. */
data class PageView(
    val folder: String,
    val docName: String,
    val spec: PageSpec,
    val imageUri: String,
    val htmlUri: String?,         // current AI digitization
    val backupUri: String?,       // one AI backup
)
