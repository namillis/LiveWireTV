package com.livewire.tv.feature.favorites.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import kotlinx.coroutines.delay

/**
 * A brief top-right confirmation shown after a favourite is added or removed (task §4). It
 * sits in the top safe area, over no cards, is not focusable, and fades out after ~2 s. The
 * star is white, never amber.
 *
 * Drive it with a monotonically-increasing [token] and the [message] to show: a new token
 * (re)shows the toast, so repeated add/remove taps each re-trigger it.
 */
@Composable
fun FavoriteToastHost(
    token: Int,
    message: String,
    modifier: Modifier = Modifier,
    // True while a surface that already confirms the change (the Channel info panel's
    // Favourite/Favourited button) covers the toast's corner; the toast would sit on its title.
    suppressed: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token <= 0) return@LaunchedEffect
        visible = true
        delay(2_000)
        visible = false
    }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible && !suppressed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical),
        ) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .background(LiveWireColors.Surface)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                    .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fav_star_outline),
                    contentDescription = null,
                    tint = LiveWireColors.OnSurfaceMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    message,
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
