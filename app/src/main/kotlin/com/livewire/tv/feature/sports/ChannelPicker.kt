package com.livewire.tv.feature.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme

/**
 * The channel picker dialog (§9.5), shared by the Sports scoreboard and Search. A [Dialog]
 * gets its own window so Back closes only the picker and D-pad focus cannot escape to the
 * content behind it. A flat 55% [scrim] dims the content (no blur, §10); a centred [surface]
 * panel (~560dp, borderStrong) holds the game title, the "On: FOX" line, then the ranked match
 * rows. The panel stays inside the 27dp vertical safe area and caps its height, so a long match
 * list scrolls INSIDE the [LazyColumn] rather than running off the top and bottom of the screen.
 * The list carries top/bottom (and side) content padding so the first and last rows' focus ring
 * and 1.02 scale are never clipped. Rows use [LiveWireSurface]; the first (best) match takes
 * initial focus. A plain empty state names the network when nothing matches.
 *
 * Pure inputs only ([game], the already-ranked [matches], [onPick], [onDismiss]) so it has no
 * dependency on any ViewModel and both screens invoke it identically. Matching/ranking is done
 * by the caller (both use [com.livewire.tv.feature.sports.data.SportsRepository.matchChannels]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChannelPicker(
    game: SportsGame,
    matches: List<ChannelMatch>,
    onPick: (ChannelMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val initialFocus = remember { FocusRequester() }
        LaunchedEffect(matches) { initialFocus.requestFocus() }
        val dialogShape = RoundedCornerShape(LiveWireDimens.RadiusDialog)
        // Room for a focused row's 1.02 scale + 2dp ring + glow, top and bottom of the list.
        val ringInset = LiveWireDimens.SpaceM

        // Full-window scrim; the panel is centred and held inside the 27dp vertical safe area.
        Box(
            Modifier
                .fillMaxSize()
                .background(LiveWireColors.Scrim)
                .padding(vertical = LiveWireDimens.SafeVertical),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = 0.9f) // cap height; the list scrolls inside
                    .wrapContentHeight() // shrink to content when the list is short
                    .padding(horizontal = LiveWireDimens.SafeHorizontal)
                    .clip(dialogShape)
                    .background(LiveWireColors.Surface)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, dialogShape)
                    .padding(LiveWireDimens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
            ) {
                Text(
                    "${game.away.abbreviation} @ ${game.home.abbreviation}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = LiveWireColors.OnSurface,
                )
                Text(
                    SportsFormat.networksLine(game),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    modifier = Modifier.padding(bottom = LiveWireDimens.SpaceS),
                )

                if (matches.isEmpty()) {
                    EmptyPickerState(game.let(SportsFormat::noChannelMessage), initialFocus, onDismiss)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        // Vertical padding leaves room for the first/last row's ring + scale;
                        // horizontal padding keeps the 1.02 scale off the panel edge.
                        contentPadding = PaddingValues(
                            start = ringInset, end = ringInset, top = ringInset, bottom = ringInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
                    ) {
                        itemsIndexed(matches) { index, m ->
                            MatchRow(
                                match = m,
                                onPick = { onPick(m) },
                                focusRequester = if (index == 0) initialFocus else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(LiveWireDimens.SpaceS))
                    CloseButton(onDismiss, focusRequester = null)
                }
            }
        }
    }
}

/** A picker row: channel name (neutral) + the "MATCHED FOX" reason overline. Wide focus scale. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchRow(match: ChannelMatch, onPick: () -> Unit, focusRequester: FocusRequester?) {
    LiveWireSurface(
        onClick = onPick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM)) {
            Text(
                match.channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            Text(
                "MATCHED ${match.matchedNetwork.uppercase()}",
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/** Empty state (§9.9): a plain-words headline naming the network, then a Close button. */
@Composable
private fun EmptyPickerState(message: String, focusRequester: FocusRequester, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
        Text(
            "Open the guide to find it manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        CloseButton(onDismiss, focusRequester)
    }
}

/** The picker's Close control: a pill-shaped [LiveWireSurface] so it carries the same focus ring. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloseButton(onDismiss: () -> Unit, focusRequester: FocusRequester?) {
    LiveWireSurface(
        onClick = onDismiss,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(50),
        modifier = Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Text(
            "✕  Close",
            style = MaterialTheme.typography.titleMedium,
            color = LiveWireColors.OnSurface,
            modifier = Modifier.padding(horizontal = LiveWireDimens.SpaceXl, vertical = LiveWireDimens.SpaceM),
        )
    }
}
