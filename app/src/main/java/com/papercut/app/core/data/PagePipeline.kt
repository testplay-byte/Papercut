package com.papercut.app.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.papercut.app.core.data.model.PageFilter
import com.papercut.app.core.data.model.PageSpec
import com.papercut.app.core.data.model.Quad
import com.papercut.app.core.domain.PageGeometry

/**
 * Applies a page's stored edits (crop quad + rotation + filter) to a decoded
 * bitmap, producing the image the user sees and the AI/PDF receive.
 *
 * Order: perspective warp -> rotation -> filter. Geometry math lives in
 * PageGeometry (pure, JVM-tested); this file only moves Android pixels.
 */
object PagePipeline {

    /** Render one page image to its edited form. Input bitmap is never recycled. */
    fun render(src: Bitmap, spec: PageSpec): Bitmap {
        var bmp = src

        spec.quad?.let { q ->
            val px = q.toPixelArray(src.width, src.height)
            if (PageGeometry.isSaneQuad(px, src.width, src.height)) {
                warp(src, px)?.let { bmp = it }
            }
        }

        if (spec.rotation % 90 == 0 && spec.rotation != 0) {
            val m = Matrix().apply { postRotate(spec.rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp && bmp !== src) bmp.recycle()
            bmp = rotated
        }

        val filtered = applyFilter(bmp, spec.filter)
        if (filtered !== bmp && bmp !== src) bmp.recycle()
        return filtered
    }

    /** Perspective warp via homography (src quad px -> bounding dst rect). */
    private fun warp(src: Bitmap, q: FloatArray): Bitmap? {
        val (w, h) = PageGeometry.outputSizePx(q)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val values = PageGeometry.homography(q, dst) ?: return null
        val m = Matrix().apply { setValues(values) }
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Color-matrix filters. ORIGINAL returns the same instance (no copy). */
    fun applyFilter(src: Bitmap, filter: PageFilter): Bitmap {
        if (filter == PageFilter.ORIGINAL) return src
        val paint = Paint().apply { colorFilter = matrixFor(filter) }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height),
            Rect(0, 0, src.width, src.height), paint)
        return out
    }

    private fun matrixFor(filter: PageFilter): ColorMatrixColorFilter = when (filter) {
        PageFilter.ORIGINAL -> ColorMatrixColorFilter(ColorMatrix())
        PageFilter.GRAYSCALE -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        PageFilter.MAGIC -> ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(1.2f)
                postConcat(ColorMatrix(floatArrayOf(
                    1.45f, 0f, 0f, 0f, 30f,
                    0f, 1.45f, 0f, 0f, 30f,
                    0f, 0f, 1.45f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            },
        )
        PageFilter.BLACK_WHITE -> ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0f)
                postConcat(ColorMatrix(floatArrayOf(
                    3.2f, 0f, 0f, 0f, -320f,
                    0f, 3.2f, 0f, 0f, -320f,
                    0f, 0f, 3.2f, 0f, -320f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            },
        )
    }

    /** Opaque white-backed bitmap for PDF pages (PDF has no transparency). */
    fun flattenForPdf(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }
}
