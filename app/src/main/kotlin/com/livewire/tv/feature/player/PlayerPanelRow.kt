package com.livewire.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.PerformanceTier

/**
 * A FLAT focusable panel row for the player's Options and Channels panels (mockups
 * option3-options / option3-channels): unlike [com.livewire.tv.ui.theme.LiveWireSurface],
 * the resting state has NO fill and NO border — the row is invisible until focused. Only the
 * focused row gets [LiveWireColors.SurfaceFocused], the 2 dp amber ring, a 1.02 scale and (on
 * the STANDARD tier) the amber glow. This matches the panels' design where the list reads as
 * plain text rows with a single highlighted selection, rather than a stack of raised cards.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerFlatRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glowOn = LiveWireTheme.tokens.tier == PerformanceTier.STANDARD
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = LiveWireColors.OnSurface,
            focusedContainerColor = LiveWireColors.SurfaceFocused,
            focusedContentColor = LiveWireColors.OnSurface,
            pressedContainerColor = LiveWireColors.SurfaceFocused,
            pressedContentColor = LiveWireColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = LiveWireDimens.FocusScaleWide),
        border = ClickableSurfaceDefaults.border(
            // Flat at rest: a transparent border, so no hairline card edge (mockup rows).
            border = Border(BorderStroke(LiveWireDimens.RestBorder, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(LiveWireDimens.FocusBorder, LiveWireColors.Accent), shape = shape),
            pressedBorder = Border(BorderStroke(LiveWireDimens.FocusBorder, LiveWireColors.Accent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = if (glowOn) {
                Glow(elevationColor = LiveWireColors.Accent.copy(alpha = 0.30f), elevation = LiveWireDimens.FocusGlow)
            } else {
                Glow.None
            },
        ),
        content = content,
    )
}
