package com.papercut.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bento palette — the single source of truth for color in Papercut.
 *
 * DARK by default (owner preference): near-black canvas, raised charcoal tiles,
 * high-contrast ink, ONE vivid accent (orange "cut") used sparingly.
 * Never introduce raw hex outside this file; screens compose from these tokens.
 */
object PaperColors {
    // Canvas & tiles (dark bento)
    val Canvas = Color(0xFF0E0E11)        // page background
    val Tile = Color(0xFF1A1A1F)          // raised tile surface
    val TilePressed = Color(0xFF232329)   // tile while pressed
    val TileSunken = Color(0xFF141418)    // inset areas (inputs, tracks)
    val TileBorder = Color(0xFF2A2A31)    // hairline separation

    // Ink (text & icons) — light on dark
    val Ink = Color(0xFFF2F2F5)           // primary text
    val InkSecondary = Color(0xFF9D9DA6)  // labels, captions
    val InkFaint = Color(0xFF8B8B97)      // disabled, hints — ≥4.5:1 on Canvas

    // Vivid accent (orange "cut" mark — matches the launcher icon)
    val Accent = Color(0xFFFF5A2E)
    val AccentSoft = Color(0x33FF5A2E)    // translucent accent wash for selected tiles

    // Semantic status
    val Success = Color(0xFF4CC38A)
    val Warning = Color(0xFFF5A623)
    val Error = Color(0xFFFF5F52)

    // Overlay surfaces (scanner/viewer keep the same family now — app is dark throughout)
    val ScrimDark = Color(0xE6000000)
    val NightCanvas = Color(0xFF0E0E11)
    val NightTile = Color(0xFF1A1A1F)
    val NightInk = Color(0xFFF2F2F5)
    val NightInkSecondary = Color(0xFF9D9DA6)
}

/** Corner radii — the bento geometry. */
object PaperRadii {
    val tile = 22.dp
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
