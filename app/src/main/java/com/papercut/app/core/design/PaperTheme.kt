package com.papercut.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Papercut theme.
 *
 * Papercut is a light-bento app by design (gray canvas + tiles); there is no dark
 * app-wide mode. Dark surfaces are explicit per-screen choices (scanner, viewer)
 * and use PaperColors.Night* tokens directly.
 *
 * The Material3 scheme is mapped to bento tokens so stock Material components
 * (dialogs, text fields) inherit the right colors instead of fighting the design.
 */
private val BentoScheme = lightColorScheme(
    primary = PaperColors.Accent,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = PaperColors.Ink,
    onSecondary = PaperColors.Canvas,
    background = PaperColors.Canvas,
    onBackground = PaperColors.Ink,
    surface = PaperColors.Tile,
    onSurface = PaperColors.Ink,
    onSurfaceVariant = PaperColors.InkSecondary,
    error = PaperColors.Error,
    outline = PaperColors.InkFaint,
)

/**
 * Typography scale.
 *
 * `statNumber` is the bento signature: big, bold, TABULAR figures so counts and
 * metrics never jitter when digits change. `fontWeight = FontWeight.Bold` plus
 * the default font's tabular feature; where the platform font lacks tabular
 * figures we accept equal-width approximation (digits in Roboto are close enough).
 */
private val PaperTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun PapercutTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BentoScheme,
        typography = PaperTypography,
        content = content,
    )
}
