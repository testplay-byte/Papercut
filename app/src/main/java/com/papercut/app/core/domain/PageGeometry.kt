package com.papercut.app.core.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pure geometry for perspective page correction — no Android types, so CI
 * unit-tests it on the JVM.
 *
 * Android's Matrix is a full 3x3, so a 4-point perspective warp IS expressible:
 * we solve the homography mapping src quad -> dst rect and feed it to
 * Matrix.setValues (drawBitmap on a Bitmap-backed Canvas applies perspective).
 */
object PageGeometry {

    /** quad corners in pixel space: TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy */
    fun outputSizePx(q: FloatArray): Pair<Int, Int> {
        fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
            hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        val w = max(dist(q[0], q[1], q[2], q[3]), dist(q[6], q[7], q[4], q[5]))
        val h = max(dist(q[0], q[1], q[6], q[7]), dist(q[2], q[3], q[4], q[5]))
        return max(w.toInt(), 16) to max(h.toInt(), 16)
    }

    /**
     * Homography taking [src] quad to [dst] quad (8 floats each, TL TR BR BL).
     * Returns the 9 values for Matrix.setValues (row-major, h8 fixed = 1).
     * Null when the system is degenerate (collapsed quad).
     *
     * DLT: u(h6x + h7y + 1) = h0x + h1y + h2   (same for v with h3..h5)
     *    → h0x + h1y + h2 - u·h6·x - u·h7·y = u
     */
    fun homography(src: FloatArray, dst: FloatArray): FloatArray? {
        require(src.size == 8 && dst.size == 8)
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble(); val y = src[2 * i + 1].toDouble()
            val u = dst[2 * i].toDouble(); val v = dst[2 * i + 1].toDouble()
            with(a[2 * i]) {
                this[0] = x; this[1] = y; this[2] = 1.0
                this[6] = -u * x; this[7] = -u * y; this[8] = u
            }
            with(a[2 * i + 1]) {
                this[3] = x; this[4] = y; this[5] = 1.0
                this[6] = -v * x; this[7] = -v * y; this[8] = v
            }
        }
        val h = solve(a) ?: return null
        return floatArrayOf(
            h[0].toFloat(), h[1].toFloat(), h[2].toFloat(),
            h[3].toFloat(), h[4].toFloat(), h[5].toFloat(),
            h[6].toFloat(), h[7].toFloat(), 1.0f,
        )
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>): DoubleArray? {
        val n = 8
        for (col in 0 until n) {
            var piv = col
            for (row in col + 1 until n) {
                if (abs(a[row][col]) > abs(a[piv][col])) piv = row
            }
            if (abs(a[piv][col]) < 1e-12) return null
            if (piv != col) { val t = a[col]; a[col] = a[piv]; a[piv] = t }
            for (row in col + 1 until n) {
                val f = a[row][col] / a[col][col]
                if (f == 0.0) continue
                for (k in col until n + 1) a[row][k] -= f * a[col][k]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = a[row][n]
            for (k in row + 1 until n) s -= a[row][k] * x[k]
            x[row] = s / a[row][row]
        }
        return x
    }

    /**
     * Sanity check before warping: quad inside image bounds (small tolerance),
     * non-degenerate (shoelace area > 3% of frame), convex sign-consistent.
     */
    fun isSaneQuad(q: FloatArray, imgW: Int, imgH: Int): Boolean {
        var i = 0
        while (i < 8) {
            if (q[i] < -0.05f * imgW || q[i] > imgW * 1.05f ||
                q[i + 1] < -0.05f * imgH || q[i + 1] > imgH * 1.05f) return false
            i += 2
        }
        var area = 0f
        for (k in 0 until 4) {
            val x1 = q[2 * k]; val y1 = q[2 * k + 1]
            val x2 = q[2 * ((k + 1) % 4)]; val y2 = q[2 * ((k + 1) % 4) + 1]
            area += x1 * y2 - x2 * y1
        }
        return abs(area) / 2f > imgW * imgH * 0.03f
    }

    /**
     * "Straighten": snap to an axis-aligned rectangle when EVERY corner sits
     * within `tol` of that rectangle's corner (small hand-drag wobble). A real
     * perspective quad (one corner far off) is returned unchanged.
     */
    fun maybeStraighten(q: FloatArray, imgW: Int, imgH: Int, tol: Float = 0.08f): FloatArray {
        val l = minOf(q[0], q[2], q[4], q[6]); val r = maxOf(q[0], q[2], q[4], q[6])
        val t = minOf(q[1], q[3], q[5], q[7]); val b = maxOf(q[1], q[3], q[5], q[7])
        val dx = imgW * tol; val dy = imgH * tol
        val near = kotlin.math.abs(q[0] - l) < dx && kotlin.math.abs(q[1] - t) < dy &&
            kotlin.math.abs(q[2] - r) < dx && kotlin.math.abs(q[3] - t) < dy &&
            kotlin.math.abs(q[4] - r) < dx && kotlin.math.abs(q[5] - b) < dy &&
            kotlin.math.abs(q[6] - l) < dx && kotlin.math.abs(q[7] - b) < dy
        return if (near) floatArrayOf(l, t, r, t, r, b, l, b) else q
    }
}
