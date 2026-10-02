package com.livewire.tv.feature.favorites.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.home.wordmark
import com.livewire.tv.feature.player.PlayerFlatRow
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the Channel info panel needs, assembled by the host screen from what it has. */
data class ChannelInfoModel(
    val channel: LiveChannel,
    /** The provider's display name (e.g. "Skylight IPTV"). */
    val providerName: String,
    /** The category label shown in the meta line, or null. */
    val categoryLabel: String?,
    /** Stream format chip ("TS"/"HLS") from Settings; null hides the chip. */
    val formatLabel: String?,
    /** Quality chip ("HD"/"FHD"/"UHD"/"SD") parsed from the name; null hides the chip. */
    val qualityLabel: String?,
    val nowPlaying: EpgProgramme?,
    val upNext: List<EpgProgramme>,
    val isFavourite: Boolean,
)

/**
 * The shared Channel info panel (mockup channel-info/optionA): a right-side slide-in sheet over
 * a strongly dimmed screen, opened from the hold-OK menu on Home, Guide and Search. It shows the
 * channel identity, cheap stream chips (FORMAT from Settings, QUALITY parsed from the name — no
 * audio language, never probing the stream), the on-now programme with progress, an up-next
 * list, and a Play + Favourite button pair. When there is no guide data it shows the empty
 * state. Back closes it; the host restores focus to the card/row that opened it (via [onDismiss]).
 *
 * Fixes vs the mockup (task §4): the backdrop is dimmed more strongly than the mockup (matching
 * the dialog scrim, so Home's hero title doesn't compete), and the hint row reads ◀▶ Move.
 */
@Composable
fun ChannelInfoPanel(
    model: ChannelInfoModel,
    now: Long,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true, onBack = onDismiss)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(modifier.fillMaxSize()) {
        // Stronger dim than the mockup: two scrim layers so the hero title behind does not
        // compete with the panel (task §4a — at least as dark as the Providers/Update dialog).
        Box(Modifier.fillMaxSize().background(LiveWireColors.Scrim))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(460.dp)
                    .clip(shape)
                    .background(LiveWireColors.Surface)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                    .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SafeVertical),
            ) {
                Header(model)
                Spacer(Modifier.height(LiveWireDimens.SpaceM))
                Chips(model)
                Spacer(Modifier.height(LiveWireDimens.SpaceL))

                Box(Modifier.weight(1f)) {
                    if (model.nowPlaying == null) {
                        EmptyState()
                    } else {
                        NowAndNext(model, now)
                    }
                }

                Spacer(Modifier.height(LiveWireDimens.SpaceM))
                // The Favourite button toggles in place (label flips, toast shows, focus stays);
                // it must NOT close the panel. Only Play and Back close it.
                Actions(model.isFavourite, playFocus, onPlay, onToggleFavourite)
                Spacer(Modifier.height(LiveWireDimens.SpaceS))
                HintRow()
            }
        }
    }
}

@Composable
private fun Header(model: ChannelInfoModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
        Box(
            Modifier
                .size(width = 72.dp, height = 44.dp)
                .clip(RoundedCornerShape(LiveWireDimens.RadiusCell))
                .background(LiveWireColors.SurfaceRaised)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusCell)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                wordmark(model.channel.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                model.channel.name,
                style = MaterialTheme.typography.headlineSmall,
                color = LiveWireColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                metaLine(model),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun metaLine(model: ChannelInfoModel): String =
    listOfNotNull(model.categoryLabel, model.providerName.takeIf { it.isNotBlank() })
        .joinToString(" · ")

@Composable
private fun Chips(model: ChannelInfoModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
        model.formatLabel?.let { Chip("FORMAT", it) }
        model.qualityLabel?.let { Chip("QUALITY", it) }
    }
}

@Composable
private fun Chip(label: String, value: String) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXs),
    ) {
        Text(label, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurfaceMuted)
        Text(value, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
    }
}

@Composable
private fun NowAndNext(model: ChannelInfoModel, now: Long) {
    val prog = model.nowPlaying ?: return
    Column(Modifier.fillMaxWidth()) {
        Text("ON NOW", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Text(prog.title, style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            progRange(prog),
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        LiveWireProgress(prog.progressAt(now), Modifier.fillMaxWidth(), height = 4.dp)
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
            Text(elapsedLabel(prog, now), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            Text(leftLabel(prog, now), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
        }

        if (model.upNext.isNotEmpty()) {
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Text("UP NEXT", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(model.upNext.take(3), key = { it.startMs }) { next -> UpNextRow(next) }
            }
        }
    }
}

@Composable
private fun UpNextRow(programme: EpgProgramme) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LiveWireDimens.RadiusCell))
            .background(LiveWireColors.SurfaceRaised)
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
    ) {
        Text(clock(programme.startMs), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.width(64.dp))
        Text(programme.title, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        programme.category?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyState() {
    Column {
        Icon(
            painter = painterResource(R.drawable.ic_fav_listings),
            contentDescription = null,
            tint = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text("No guide listings for this channel", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface, maxLines = 2)
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            "We don't have programme information for this channel right now. You can still play it live.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun Actions(isFavourite: Boolean, playFocus: FocusRequester, onPlay: () -> Unit, onToggleFavourite: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
        ActionButton(
            iconRes = R.drawable.ic_fav_play,
            label = "Play",
            onClick = onPlay,
            modifier = Modifier.focusRequester(playFocus),
        )
        ActionButton(
            iconRes = if (isFavourite) R.drawable.ic_fav_star_filled else R.drawable.ic_fav_star_outline,
            label = if (isFavourite) "Favourited" else "Favourite",
            onClick = onToggleFavourite,
        )
    }
}

@Composable
private fun ActionButton(iconRes: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier,
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null, tint = LiveWireColors.OnSurface, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, maxLines = 1)
        }
    }
}

@Composable
private fun HintRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL)) {
        Hint("OK", "Select")
        // Task §4b: the panel is navigated with Left/Right, so the hint reads Move, not up/down.
        Hint("◀ ▶", "Move")
        Hint("Back", "Close")
    }
}

@Composable
private fun Hint(keys: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(LiveWireColors.SurfaceRaised).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(keys, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

private fun progRange(p: EpgProgramme): String {
    val range = "${clock(p.startMs)} – ${clock(p.stopMs)}"
    return p.category?.takeIf { it.isNotBlank() }?.let { "$range · $it" } ?: range
}

private fun elapsedLabel(p: EpgProgramme, now: Long): String {
    val mins = ((now - p.startMs) / 60_000L).coerceAtLeast(0)
    return "$mins min elapsed"
}

private fun leftLabel(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

private fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
