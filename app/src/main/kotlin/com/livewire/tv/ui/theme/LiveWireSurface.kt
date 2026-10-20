package com.livewire.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface

/**
 * The one focusable surface in LiveWire. Every card, cell, button and row goes through this
 * so focus looks the same everywhere: resting = [restingColor] with a hairline border;
 * focused = the light [LiveWireColors.Focus] fill with dark ink. No ring, no glow, no scale,
 * so the LOW tier and STANDARD tier look the same.
 *
 * Content reads [LiveWireTheme.onSurface] / [LiveWireTheme.onSurfaceMuted] to flip with focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveWireSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
    restingColor: Color = LiveWireColors.SurfaceRaised,
    @Suppress("UNUSED_PARAMETER") focusedScale: Float = LiveWireDimens.FocusScale,
    onLongClick: (() -> Unit)? = null,
    restingBorder: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rest = if (restingBorder) {
        Border(BorderStroke(LiveWireDimens.RestBorder, LiveWireColors.Border), shape = shape)
    } else {
        Border.None
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = restingColor,
            contentColor = LiveWireColors.OnSurface,
            focusedContainerColor = LiveWireColors.Focus,
            focusedContentColor = LiveWireColors.OnFocus,
            pressedContainerColor = LiveWireColors.Focus,
            pressedContentColor = LiveWireColors.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(border = rest, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
    ) {
        ProvideOnFocus(focused) { content() }
    }
}

/**
 * The B · Grid progress bar: a light line that runs into the red live dot. On the focus fill
 * the line turns dark and the dot gets a light ring. [soft] is the dimmer line used by the
 * mini bars in lists.
 */
@Composable
fun LiveWireProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    dot: Dp = height * 2.4f,
    soft: Boolean = false,
) {
    val f = fraction.coerceIn(0f, 1f)
    val onFocus = LocalOnFocus.current
    val pill = RoundedCornerShape(50)
    val fill = if (soft && !onFocus) LiveWireColors.ProgressFillSoft else LiveWireTheme.progressFill
    val ring = if (onFocus) LiveWireColors.Focus else LiveWireColors.Canvas
    Box(modifier.height(dot), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).background(LiveWireTheme.progressTrack, pill))
        Box(Modifier.fillMaxWidth(f).height(height).background(fill, pill))
        Layout(
            content = {
                Box(
                    Modifier
                        .size(dot)
                        .background(ring, CircleShape)
                        .padding(dot * 0.2f)
                        .background(LiveWireColors.Live, CircleShape),
                )
            },
        ) { measurables, constraints ->
            val p = measurables.first().measure(Constraints())
            layout(constraints.maxWidth, p.height) {
                val x = (constraints.maxWidth * f).toInt() - p.width / 2
                p.place(x, 0)
            }
        }
    }
}
