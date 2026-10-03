package com.papercut.app.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bento building blocks. One implementation, reused everywhere —
 * the old app hand-copied these 5+ times per screen.
 *
 * Interaction rule: tiles react to touch with a soft SCALE, never a ripple or
 * focus outline (the "weird border on tap" the old UI suffered from).
 */

/** Scale-down while pressed; used instead of ripple/highlight overlays. */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(PaperMotion.PRESS_MS),
        label = "pressScale",
    )
    return this.scale(scale)
}

/** App-wide click: scale feedback only, zero ripple/outline (the old "weird border" fix). */
@Composable
fun Modifier.tap(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
)

/** The core bento tile: rounded raised surface with soft shadow. */
@Composable
fun BentoTile(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(PaperRadii.tile)
    Column(
        modifier = modifier
            .pressScale(interaction)
            .shadow(elevation = if (selected) 3.dp else 1.5.dp, shape = shape)
            .background(if (selected) PaperColors.AccentSoft else PaperColors.Tile, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null, // no ripple — the scale IS the feedback
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(PaperGap.m),
        content = content,
    )
}

/** Big tabular number for counts/metrics — the bento signature element. */
@Composable
fun StatNumber(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = PaperColors.Ink,
) {
    Text(
        text = value,
        modifier = modifier,
        color = color,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum", // tabular figures: digits never jitter
        letterSpacing = (-1).sp,
    )
}

/** Small uppercase caption used under stat numbers and on section headers. */
@Composable
fun StatCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = PaperColors.InkSecondary,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Segmented pill selector — replaces the old 5x-copied toggle markup. */
@Composable
fun <T> SegmentedPill(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
) {
    val trackColor = if (darkSurface) PaperColors.NightTile else PaperColors.TileSunken
    val activeColor = if (darkSurface) PaperColors.NightInk else PaperColors.Tile
    val inactiveInk = if (darkSurface) PaperColors.NightInkSecondary else PaperColors.InkSecondary
    val activeInk = if (darkSurface) PaperColors.NightCanvas else PaperColors.Ink

    Row(
        modifier = modifier
            .background(RoundedCornerShape(PaperRadii.pill), color = trackColor)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pressScale(interaction, pressedScale = 0.95f)
                    .background(
                        if (isSelected) activeColor else Color.Transparent,
                        RoundedCornerShape(PaperRadii.pill),
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) activeInk else inactiveInk,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** iOS-style switch rebuilt on tokens: animated track + knob. */
@Composable
fun SmoothSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(
        targetValue = if (checked) PaperColors.Accent else PaperColors.TileSunken,
        animationSpec = tween(PaperMotion.SWAP_MS),
        label = "switchTrack",
    )
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 20f else 0f,
        animationSpec = tween(PaperMotion.SWAP_MS),
        label = "switchKnob",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp, 28.dp)
            .pressScale(interaction, pressedScale = 0.94f)
            .background(RoundedCornerShape(PaperRadii.pill), color = track)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .offset(x = knobOffset.dp)
                .size(24.dp)
                .shadow(2.dp, RoundedCornerShape(PaperRadii.pill))
                .background(Color.White, RoundedCornerShape(PaperRadii.pill)),
        )
    }
}

/**
 * Compact status indicator for scan cards / viewer header.
 * Exactly one badge per item — the old app stacked two overlays at once.
 */
enum class ScanStatus { Plain, Queued, Processing, Done, Error }

@Composable
fun StatusBadge(status: ScanStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        ScanStatus.Plain -> return
        ScanStatus.Queued -> "Queued" to PaperColors.InkFaint
        ScanStatus.Processing -> "Working" to PaperColors.Warning
        ScanStatus.Done -> "Digitized" to PaperColors.Success
        ScanStatus.Error -> "Failed" to PaperColors.Error
    }
    Text(
        text = label,
        modifier = modifier
            .background(RoundedCornerShape(PaperRadii.pill), color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Page header: large title + optional caption, consistent across screens. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = PaperColors.Ink,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = PaperColors.InkSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
