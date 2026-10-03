package com.papercut.app.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Papercut theme — the app is DARK by design (owner preference).
 * No light mode, no dynamic color: fixed tokens keep the identity exact.
 * The Material3 scheme is mapped to the bento palette so stock components
 * (dialogs, text fields, cursors) inherit the right colors instead of
 * fighting the design.
 */
private val BentoScheme = darkColorScheme(
    primary = PaperColors.Accent,
    onPrimary = Color.White,
    secondary = PaperColors.Ink,
    onSecondary = PaperColors.Canvas,
    background = PaperColors.Canvas,
    onBackground = PaperColors.Ink,
    surface = PaperColors.Tile,
    onSurface = PaperColors.Ink,
    onSurfaceVariant = PaperColors.InkSecondary,
    surfaceVariant = PaperColors.TileSunken,
    error = PaperColors.Error,
    outline = PaperColors.InkFaint,
)

private val PaperTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

@Composable
fun PapercutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BentoScheme,
        typography = PaperTypography,
        content = content,
    )
}
