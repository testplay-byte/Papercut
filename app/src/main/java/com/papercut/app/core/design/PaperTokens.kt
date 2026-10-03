package com.papercut.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bento palette — the single source of truth for color in Papercut.
 *
 * System: an iOS-gray canvas with elevated rounded tiles, near-black ink,
 * and ONE vivid accent used sparingly (active states, primary numbers, CTAs).
 * Never introduce raw hex outside this file; screens compose from these tokens.
 */
object PaperColors {
    // Canvas & tiles
    val Canvas = Color(0xFFEDEDF0)        // iOS-gray page background
    val Tile = Color(0xFFFAFAFC)          // raised tile surface
    val TilePressed = Color(0xFFE9E9ED)   // tile while pressed
    val TileSunken = Color(0xFFE2E2E7)    // inset areas (inputs, track)

    // Ink (text & icons)
    val Ink = Color(0xFF1C1C1E)           // primary text
    val InkSecondary = Color(0xFF6E6E73)  // labels, captions
    val InkFaint = Color(0xFFAEAEB2)      // disabled, hints

    // Vivid accent (orange "cut" mark — matches the launcher icon)
    val Accent = Color(0xFFFF4F26)
    val AccentSoft = Color(0xFFFFE3DB)    // accent-tinted fill for selected tiles

    // Semantic status
    val Success = Color(0xFF34A853)
    val Warning = Color(0xFFF5A623)
    val Error = Color(0xFFD93025)

    // Overlay surfaces (full-screen dark screens: scanner, viewer, scrims)
    val ScrimDark = Color(0xCC16161A)
    val NightCanvas = Color(0xFF16161A)
    val NightTile = Color(0xFF232328)
    val NightInk = Color(0xFFF2F2F5)
    val NightInkSecondary = Color(0xFF9A9AA0)
}

/** Corner radii — the bento geometry. */
object PaperRadii {
    val tile = 24.dp
    val small = 14.dp
    val pill = 999.dp
}

/** Standard spacing scale. */
object PaperGap {
    val xs = 6.dp
    val s = 10.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
}

/** Shared animation durations (ms) so motion feels consistent everywhere. */
object PaperMotion {
    const val PRESS_MS = 120
    const val SWAP_MS = 220
    const val ENTER_MS = 320
    const val EXIT_MS = 200
}
