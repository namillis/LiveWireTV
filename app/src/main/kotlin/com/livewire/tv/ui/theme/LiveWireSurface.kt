package com.livewire.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface

/**
 * The one focusable surface in LiveWire (design system section 6). Every card, cell,
 * button and row goes through this so focus looks the same everywhere:
 * resting = [restingColor] with a hairline border; focused = [LiveWireColors.SurfaceFocused],
 * a 2dp amber border, [focusedScale] and (STANDARD tier only) a static amber glow.
 *
 * Callers must not clip this inside a parent smaller than the scaled size.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveWireSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
    restingColor: Color = LiveWireColors.SurfaceRaised,
    focusedScale: Float = LiveWireDimens.FocusScale,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glowOn = LiveWireTheme.tokens.tier == PerformanceTier.STANDARD
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = restingColor,
            contentColor = LiveWireColors.OnSurface,
            focusedContainerColor = LiveWireColors.SurfaceFocused,
            focusedContentColor = LiveWireColors.OnSurface,
            pressedContainerColor = LiveWireColors.SurfaceFocused,
            pressedContentColor = LiveWireColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(LiveWireDimens.RestBorder, LiveWireColors.Border), shape = shape),
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

/** A thin neutral progress bar (section 7). Never amber, never red. */
@Composable
fun LiveWireProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val pill = RoundedCornerShape(50)
    Box(modifier.height(height).background(LiveWireColors.ProgressTrack, pill)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(LiveWireColors.ProgressFill, pill),
        )
    }
}
