package com.livewire.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlaybackStatus
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.core.player.VideoFormat
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.home.brandTint
import com.livewire.tv.feature.home.wordmark
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireTheme

/** All the strings/data the resting overlay renders. Built by the screen from view-model state. */
data class RestingOverlayModel(
    val channelName: String,
    val channelNumber: String,
    val nowPlaying: EpgProgramme?,
    val upNext: EpgProgramme?,
    val video: VideoFormat?,
    val audioChannelCount: Int,
    val hasSubtitles: Boolean,
)

/**
 * The resting player overlay (design system §9.6, mockup option3.png): top-left channel-number
 * chip + LIVE, top-right clock; a bottom block with the channel mark/name, the "CH n · 1080p ·
 * Stereo · CC" sub-line, ON NOW title + category, a 2-line description, a grey progress bar with
 * start/end times, UP NEXT, hint chips and the two edge hints. Sits over a bottom gradient.
 *
 * Purely presentational: [visible] gates it, [now] drives the clock/progress, and the screen
 * owns auto-hide and key handling.
 */
@Composable
fun RestingOverlay(
    model: RestingOverlayModel,
    status: PlaybackStatus,
    now: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // Bottom gradient so the text stays legible over any video (design §9.6).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(460.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to LiveWireColors.Canvas.copy(alpha = 0.55f),
                        1f to LiveWireColors.Canvas.copy(alpha = 0.94f),
                    ),
                ),
        )

        TopBug(model.channelNumber, status)
        Clock(now, Modifier.align(Alignment.TopEnd))
        EdgeHint("◀", "Channels", start = true, modifier = Modifier.align(Alignment.CenterStart))
        EdgeHint("▶", "Options", start = false, modifier = Modifier.align(Alignment.CenterEnd))

        BottomBlock(model, status, now, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun TopBug(channelNumber: String, status: PlaybackStatus) {
    Row(
        Modifier.padding(start = LiveWireDimens.SafeHorizontal, top = LiveWireDimens.SafeVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
    ) {
        if (channelNumber.isNotBlank()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(5.dp))
                    .padding(horizontal = LiveWireDimens.SpaceS, vertical = 4.dp),
            ) {
                Text("CH $channelNumber", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
            }
        }
        if (status.isLive && status.state != PlaybackState.PAUSED) LivePill()
        if (status.state == PlaybackState.PAUSED) PausedPill()
    }
}

@Composable
private fun LivePill() {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(LiveWireColors.Live)
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Text("LIVE", style = LiveWireTheme.tokens.tag, color = Color.White)
    }
}

@Composable
private fun PausedPill() {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, RoundedCornerShape(50))
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 4.dp),
    ) {
        Text("❚❚ PAUSED", style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
    }
}

@Composable
private fun Clock(now: Long, modifier: Modifier) {
    Text(
        PlayerFormats.clockMeridiem(now),
        style = MaterialTheme.typography.labelMedium,
        color = LiveWireColors.OnSurface,
        modifier = modifier.padding(end = LiveWireDimens.SafeHorizontal, top = LiveWireDimens.SafeVertical),
    )
}

@Composable
private fun EdgeHint(arrow: String, label: String, start: Boolean, modifier: Modifier) {
    Row(
        modifier.padding(horizontal = LiveWireDimens.SafeHorizontal)
            .clip(RoundedCornerShape(6.dp))
            .background(LiveWireColors.Canvas.copy(alpha = 0.6f))
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
    ) {
        if (start) {
            Text(arrow, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
        } else {
            Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            Text(arrow, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun BottomBlock(model: RestingOverlayModel, status: PlaybackStatus, now: Long, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical),
    ) {
        // Channel identity: mark + name + sub-line.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL)) {
            Text(
                wordmark(model.channelName),
                style = MaterialTheme.typography.headlineSmall,
                color = brandTint(model.channelName).brightenForText(),
                maxLines = 1,
            )
            Column {
                Text(
                    model.channelName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subline = PlayerFormats.channelSubline(
                    channelNumber = model.channelNumber,
                    video = model.video,
                    audioChannelCount = model.audioChannelCount,
                    hasSubtitles = model.hasSubtitles,
                )
                if (subline.isNotBlank()) {
                    Text(subline, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        NowNext(model, now)
    }
}

@Composable
private fun NowNext(model: RestingOverlayModel, now: Long) {
    val nowPlaying = model.nowPlaying
    if (nowPlaying == null) {
        // No EPG: show the "on now" label with a clear no-info line, no empty gaps (requirement 1).
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
            Text("ON NOW", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
            Text("No programme information", style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted)
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        HintRow()
        return
    }

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
        Text("ON NOW", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
        Text(
            nowPlaying.title,
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        nowPlaying.category?.takeIf { it.isNotBlank() }?.let {
            Text("· $it", style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
        }
    }

    nowPlaying.description?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 900.dp),
        )
    }

    Spacer(Modifier.height(LiveWireDimens.SpaceM))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
        Text(PlayerFormats.clock(nowPlaying.startMs), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
        LiveWireProgress(nowPlaying.progressAt(now), Modifier.weight(1f), height = 4.dp)
        Text(PlayerFormats.clockMeridiem(nowPlaying.stopMs), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }

    Spacer(Modifier.height(LiveWireDimens.SpaceM))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        model.upNext?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
                Text("UP NEXT", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
                Text(
                    next.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Text("· ${PlayerFormats.clockMeridiem(next.startMs)}", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        HintRow()
    }
}

@Composable
private fun HintRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
        HintChip("OK", "Pause")
        HintChip("▲▼", "Channel")
    }
}

@Composable
private fun HintChip(key: String, label: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LiveWireColors.Canvas.copy(alpha = 0.6f))
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(LiveWireColors.SurfaceRaised)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(key, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** Lighten a dark brand colour so the wordmark stays legible over the gradient. */
private fun Color.brightenForText(): Color {
    val f = 0.45f
    return Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, 1f)
}
