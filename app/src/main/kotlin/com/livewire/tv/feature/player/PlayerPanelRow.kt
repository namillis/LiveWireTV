package com.livewire.tv.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.livewire.tv.ui.theme.LiveWireSurface

/**
 * A FLAT focusable panel row for the player's Options and Channels panels: unlike a plain
 * [LiveWireSurface], the resting state has no fill and no border, so the list reads as plain
 * text rows. The focused row gets the light focus fill like everywhere else.
 */
@Composable
fun PlayerFlatRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    LiveWireSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        restingColor = Color.Transparent,
        restingBorder = false,
        content = content,
    )
}
