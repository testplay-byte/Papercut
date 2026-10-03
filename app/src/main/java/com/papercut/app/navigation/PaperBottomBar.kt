package com.papercut.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.pressScale

/**
 * Bento bottom bar: two tile tabs + one raised accent scan FAB in the middle.
 * The FAB is the whole app's heartbeat — every route can reach the camera from here.
 */
@Composable
fun PaperBottomBar(
    selected: Int,
    onLibrary: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color.Transparent),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PaperGap.l, vertical = PaperGap.s)
                .shadow(6.dp, RoundedCornerShape(PaperRadii.pill))
                .background(PaperColors.Tile, RoundedCornerShape(PaperRadii.pill))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarTab(
                label = "Library",
                iconSelected = Icons.Filled.Folder,
                iconUnselected = Icons.Outlined.Folder,
                isSelected = selected == 0,
                onClick = onLibrary,
            )
            // center gap reserved for the FAB
            Box(modifier = Modifier.size(56.dp))
            BarTab(
                label = "Settings",
                iconSelected = Icons.Filled.Settings,
                iconUnselected = Icons.Outlined.Settings,
                isSelected = selected == 1,
                onClick = onSettings,
            )
        }

        // Accent scan FAB floating over the bar's center
        val fabInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .offset(y = (-18).dp)
                .size(58.dp)
                .pressScale(fabInteraction, pressedScale = 0.92f)
                .shadow(8.dp, CircleShape)
                .background(PaperColors.Accent, CircleShape)
                .clickable(interactionSource = fabInteraction, indication = null, onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DocumentScanner,
                contentDescription = "Scan",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun BarTab(
    label: String,
    iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
    iconUnselected: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .background(
                if (isSelected) PaperColors.AccentSoft else Color.Transparent,
                RoundedCornerShape(PaperRadii.pill),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = if (isSelected) iconSelected else iconUnselected,
            contentDescription = label,
            tint = if (isSelected) PaperColors.Accent else PaperColors.InkSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = if (isSelected) PaperColors.Accent else PaperColors.InkSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}
