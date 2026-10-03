package com.papercut.app.core.domain

import android.view.Surface
import kotlin.math.abs

/**
 * Smooth device-orientation tracker for the scanner — replaces the old 4-way
 * quantizer that glitched around 45° (icons jittered, capture rotation flipped).
 *
 * Three layers of calm:
 *  1. Low-pass filter on the raw angle: per-event the smoothed value eases only
 *     `smoothing` fraction of the way toward the target, and wrapping is done
 *     along the short arc (350° -> 10° doesn't sweep the long way).
 *  2. Hysteresis on the QUADRANT (the value fed to ImageCapture.targetRotation):
 *     a quadrant switch is accepted only once the smoothed angle is clearly
 *     inside the new zone (past the boundary by `deadbandDeg`). Hovering near a
 *     boundary — exactly the 45° problem — keeps the previous stable quadrant.
 *  3. Icons counter-rotate with the continuous `smoothedDeg`, not the quadrant,
 *     so the visual follows the hand fluidly while capture stays rock-steady.
 *
 * Pure logic, no Android sensors inside — feed it from an OrientationEventListener.
 * Angles: degrees, -180..180, 0 = portrait upright, positive = tilted clockwise.
 */
class OrientationTracker(
    private val smoothing: Float = 0.25f,
    private val deadbandDeg: Float = 12f,
) {
    /** Smoothed continuous angle, -180..180. Use for icon rotation. */
    var smoothedDeg: Float = 0f
        private set

    /** Stable quantized rotation for ImageCapture.targetRotation. */
    var targetRotation: Int = Surface.ROTATION_0
        private set

    fun onRawAngle(rawDeg: Float) {
        if (rawDeg < 0) return // OrientationEventListener.ORIENTATION_UNKNOWN

        val target = wrap180(rawDeg)
        val delta = wrap180(target - smoothedDeg)
        smoothedDeg = wrap180(smoothedDeg + delta * smoothing)

        maybeUpdateQuadrant()
    }

    /** Call when (re)opening the camera. */
    fun reset(angleDeg: Float = 0f) {
        smoothedDeg = wrap180(angleDeg)
        targetRotation = quadrantFor(smoothedDeg)
    }

    private fun maybeUpdateQuadrant() {
        val desired = quadrantFor(smoothedDeg)
        if (desired == targetRotation) return
        // switch only when clearly inside the desired zone, past the deadband
        val insideMargin = zoneMargin(smoothedDeg, desired)
        if (insideMargin >= deadbandDeg) targetRotation = desired
    }

    // ---- pure helpers (zone edges at ±45/±135; margin = how deep inside) ----

    private fun quadrantFor(deg: Float): Int = when {
        abs(deg) <= 45f -> Surface.ROTATION_0
        deg > 45f && deg < 135f -> Surface.ROTATION_270 // tilted clockwise => image CCW
        deg >= 135f || deg <= -135f -> Surface.ROTATION_180
        else -> Surface.ROTATION_90
    }

    /** Distance of `deg` inside the chosen quadrant's edges (negative near edges). */
    private fun zoneMargin(deg: Float, quadrant: Int): Float = when (quadrant) {
        Surface.ROTATION_0 -> 45f - abs(deg)
        Surface.ROTATION_270 -> minOf(deg - 45f, 135f - deg)          // deg in (45,135)
        Surface.ROTATION_90 -> minOf(-45f - deg, deg + 135f)          // deg in (-135,-45)
        else -> abs(deg) - 135f                                        // |deg| > 135
    }

    private fun wrap180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }
}
