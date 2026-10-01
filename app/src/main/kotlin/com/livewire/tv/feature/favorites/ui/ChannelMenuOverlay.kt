package com.livewire.tv.feature.favorites.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.feature.player.PlayerFlatRow
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/**
 * The hold-OK channel menu (mockup option2-home): a small panel with the channel name as a
 * subtitle, then three rows — Favourite (focused by default, label flips to "Remove from
 * Favourites" when already a favourite), Play, Channel info. The rest of the screen is dimmed
 * with [LiveWireColors.Scrim]. Back closes the menu; the caller restores focus to the item
 * the menu was opened from.
 *
 * This is a focus-trapping overlay drawn by the owning screen (Home, Guide, Search) above its
 * content, so it needs no Dialog window and composes in the same tree — which keeps D-pad
 * focus predictable on TV. Rows reuse [PlayerFlatRow] so the focused row carries the one amber
 * ring (design system §6); the panel itself is a plain [LiveWireColors.Surface] card (§9.5).
 */
@Composable
fun ChannelMenuOverlay(
    channel: LiveChannel,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onPlay: () -> Unit,
    onChannelInfo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true, onBack = onDismiss)

    val favouriteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { favouriteFocus.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .background(LiveWireColors.Scrim),
    ) {
        val shape = RoundedCornerShape(LiveWireDimens.RadiusDialog)
        Column(
            Modifier
                // Anchored near the top-left content area, like the mockup; it never covers the
                // hero band above it, and the scrim dims everything behind.
                .padding(start = LiveWireDimens.SafeHorizontal + 72.dp, top = 300.dp)
                .widthIn(min = 260.dp, max = 320.dp)
                .clip(shape)
                .background(LiveWireColors.Surface)
                .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                .padding(LiveWireDimens.SpaceS)
                .focusGroup(),
        ) {
            Text(
                channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = LiveWireDimens.SpaceM,
                    vertical = LiveWireDimens.SpaceS,
                ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LiveWireDimens.SpaceS)
                    .height(1.dp)
                    .background(LiveWireColors.Border),
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))

            MenuRow(
                iconRes = if (isFavourite) R.drawable.ic_fav_star_filled else R.drawable.ic_fav_star_outline,
                label = FavoritesUi.favouriteActionLabel(isFavourite),
                focusRequester = favouriteFocus,
                onClick = { onToggleFavourite(); onDismiss() },
            )
            MenuRow(
                iconRes = R.drawable.ic_fav_play,
                label = "Play",
                onClick = { onPlay(); onDismiss() },
            )
            MenuRow(
                iconRes = R.drawable.ic_fav_info,
                label = "Channel info",
                // Does NOT dismiss here: the host closes the menu as it opens the Channel info
                // panel, so the menu's own dismiss (which restores grid focus) never fires and
                // fights the panel for focus.
                onClick = onChannelInfo,
            )
        }
    }
}

@Composable
private fun MenuRow(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    PlayerFlatRow(
        onClick = onClick,
        modifier = (focusRequester?.let { modifier.focusRequester(it) } ?: modifier),
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = LiveWireColors.OnSurface,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
