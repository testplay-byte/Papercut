package com.papercut.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the perspective pipeline decisions — the math that decides
 * whether a crop warp happens and where the pixels land. CI runs these on
 * every push before any APK is produced.
 */
class PageGeometryTest {

    private fun assertClose(a: Float, b: Float, eps: Float = 1e-3f) =
        assertTrue("expected $a ≈ $b", kotlin.math.abs(a - b) < eps)

    @Test
    fun `identity quad maps exactly to destination rect`() {
        val src = floatArrayOf(0f, 0f, 100f, 0f, 100f, 200f, 0f, 200f)
        val dst = floatArrayOf(0f, 0f, 100f, 0f, 100f, 200f, 0f, 200f)
        val h = PageGeometry.homography(src, dst)
        assertNotNull("homography exists", h)
        // apply h to each src point (x', y') = ( (h0x+h1y+h2)/w, (h3x+h4y+h5)/w )
        fun map(px: Float, py: Float): Pair<Float, Float> {
            val w = h!![6] * px + h[7] * py + h[8]
            return Pair((h[0] * px + h[1] * py + h[2]) / w, (h[3] * px + h[4] * py + h[5]) / w)
        }
        val p = map(25f, 50f)
        assertClose(p.first, 25f); assertClose(p.second, 50f)
        val c = map(100f, 200f)
        assertClose(c.first, 100f); assertClose(c.second, 200f)
    }

    @Test
    fun `skewed quad maps its corners onto the rect corners`() {
        // trapezoid page photo: corners TL(10,20) TR(90,10) BR(100,190) BL(0,180)
        val src = floatArrayOf(10f, 20f, 90f, 10f, 100f, 190f, 0f, 180f)
        val (w, hgt) = PageGeometry.outputSizePx(src)
        assertTrue(w > 50 && hgt > 50)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), hgt.toFloat(), 0f, hgt.toFloat())
        val hm = PageGeometry.homography(src, dst)!!
        fun map(px: Float, py: Float): Pair<Float, Float> {
            val z = hm[6] * px + hm[7] * py + hm[8]
            return Pair((hm[0] * px + hm[1] * py + hm[2]) / z, (hm[3] * px + hm[4] * py + hm[5]) / z)
        }
        // each source corner must land on its destination corner
        for (i in 0 until 4) {
            val m = map(src[2 * i], src[2 * i + 1])
            assertClose(m.first, dst[2 * i], 0.5f)
            assertClose(m.second, dst[2 * i + 1], 0.5f)
        }
    }

    @Test
    fun `degenerate collinear quad has no homography`() {
        val pts = floatArrayOf(0f, 0f, 50f, 50f, 100f, 100f, 150f, 150f)
        assertNull(PageGeometry.homography(pts, floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)))
    }

    @Test
    fun `isSaneQuad rejects collapsed and off-frame quads`() {
        val ok = floatArrayOf(5f, 5f, 95f, 5f, 95f, 95f, 5f, 95f)
        assertTrue(PageGeometry.isSaneQuad(ok, 100, 100))
        val collapsed = floatArrayOf(50f, 50f, 51f, 50f, 51f, 51f, 50f, 51f)
        assertTrue(!PageGeometry.isSaneQuad(collapsed, 100, 100))
        val offFrame = floatArrayOf(-20f, 0f, 95f, 0f, 95f, 95f, 0f, 95f)
        assertTrue(!PageGeometry.isSaneQuad(offFrame, 100, 100))
    }

    @Test
    fun `outputSize takes longest edge pairs`() {
        val q = floatArrayOf(0f, 0f, 100f, 0f, 100f, 60f, 0f, 60f)
        val (w, h) = PageGeometry.outputSizePx(q)
        assertEquals(100, w); assertEquals(60, h)
    }

    @Test
    fun `straighten snaps near-rectangular quads and keeps perspective ones`() {
        val nearly = floatArrayOf(3f, 4f, 98f, 2f, 100f, 97f, 1f, 95f)
        val snapped = PageGeometry.maybeStraighten(nearly, 100, 100)
        assertEquals(snapped[0], snapped[6], 0.01f) // TLx == BLx after snap
        assertEquals(snapped[1], snapped[3], 0.01f) // TLy == TRy
        // a real perspective quad stays untouched
        val persp = floatArrayOf(30f, 5f, 70f, 15f, 90f, 90f, 10f, 80f)
        val kept = PageGeometry.maybeStraighten(persp, 100, 100)
        assertEquals(kept[0], 30f, 0.01f)
    }
}
