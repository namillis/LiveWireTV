package com.livewire.tv.feature.favorites.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.livewire.tv.R
import com.livewire.tv.ui.theme.LiveWireColors

/**
 * The favourite marker (design system §3.3, task §4): a small OUTLINE star shown after a
 * favourite channel's name on cards, in the guide, and in search results. It is white
 * ([LiveWireColors.OnSurfaceMuted]), never amber — amber means focus — and never red.
 *
 * Rendered only when the channel is a favourite; callers guard on their `favoriteIds` set.
 */
@Composable
fun FavoriteStar(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    tint: androidx.compose.ui.graphics.Color = LiveWireColors.OnSurfaceMuted,
) {
    Icon(
        painter = painterResource(R.drawable.ic_fav_star_outline),
        contentDescription = "Favourite",
        tint = tint,
        modifier = modifier.size(size),
    )
}
