package com.papercut.app.core.domain

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the smooth orientation tracker — including the 45°
 * scenario that glitched in the old KSCAN build.
 */
class OrientationTrackerTest {

    private fun settle(t: OrientationTracker, deg: Float, ticks: Int = 120) {
        repeat(ticks) { t.onRawAngle(deg) }
    }

    @Test
    fun `upright portrait stays ROTATION_0`() {
        val t = OrientationTracker()
        settle(t, 2f)
        assertEquals(Surface.ROTATION_0, t.targetRotation)
    }

    @Test
    fun `hovering at 45 degrees never flips the quadrant`() {
        val t = OrientationTracker()
        // approach 45° gradually from portrait
        settle(t, 44f)
        val before = t.targetRotation
        // jitter hard around 45°, the exact old failure mode
        listOf(46f, 43f, 47f, 44f, 46f, 45f).forEach { deg ->
            repeat(10) { t.onRawAngle(deg) }
        }
        assertEquals(before, t.targetRotation)
    }

    @Test
    fun `committed landscape right rotates once past the deadband`() {
        val t = OrientationTracker()
        settle(t, 70f)
        assertEquals(Surface.ROTATION_270, t.targetRotation)
    }

    @Test
    fun `angle wraps the short way across 180 and keeps smoothing finite`() {
        val t = OrientationTracker()
        settle(t, 179f)
        settle(t, -179f) // 2° away across the seam, must not swing through 0
        assertEquals(Surface.ROTATION_180, t.targetRotation)
        assertTrue(t.smoothedDeg > 150f || t.smoothedDeg < -150f)
    }

    @Test
    fun `unknown orientation readings are ignored`() {
        val t = OrientationTracker()
        settle(t, 90f)
        val snap = t.smoothedDeg
        repeat(50) { t.onRawAngle(-1f) }
        assertEquals(snap, t.smoothedDeg, 0.001f)
    }
}
