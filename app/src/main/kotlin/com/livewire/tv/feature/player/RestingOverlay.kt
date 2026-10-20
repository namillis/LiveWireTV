package com.livewire.tv.feature.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlaybackStatus
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.core.player.VideoFormat
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EventTimes
import com.livewire.tv.feature.home.ChannelLogo
import com.livewire.tv.feature.home.wordmark
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveBadge
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.Overline

/** All the strings/data the resting overlay renders. Built by the screen from view-model state. */
data class RestingOverlayModel(
    val channelName: String,
    val channelNumber: String,
    val nowPlaying: EpgProgramme?,
    val upNext: EpgProgramme?,
    val video: VideoFormat?,
    val audioChannelCount: Int,
    val hasSubtitles: Boolean,
    /** The playing channel, for its logo; null while the source is still resolving. */
    val channel: LiveChannel? = null,
)

/**
 * The resting player overlay (mockup B-grid-30): top-left channel number + LIVE badge,
 * top-right clock, edge hints for the channel list (Left) and Options (Right). The bottom block
 * holds the channel logo, name and "CH n · 1080p · STEREO · CC" line, the ON NOW title and
 * genre, a short description, the full-width progress line into the red dot with start time,
 * minutes left and end time, UP NEXT and key hints, all over a bottom gradient.
 *
 * Purely presentational: the screen owns auto-hide and key handling.
 */
@Composable
fun RestingOverlay(
    model: RestingOverlayModel,
    status: PlaybackStatus,
    now: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(90.dp)
                .background(Brush.verticalGradient(0f to LiveWireColors.Canvas.copy(alpha = 0.7f), 1f to LiveWireColors.Canvas.copy(alpha = 0f))),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .background(
                    Brush.verticalGradient(
                        0f to LiveWireColors.Canvas.copy(alpha = 0f),
                        0.45f to LiveWireColors.Canvas.copy(alpha = 0.78f),
                        1f to LiveWireColors.Canvas.copy(alpha = 0.94f),
                    ),
                ),
        )

        TopBug(model.channelNumber, status)
        PlayerClock(now, Modifier.align(Alignment.TopEnd))
        EdgeHint("◀", "Channels", start = true, modifier = Modifier.align(Alignment.CenterStart))
        EdgeHint("▶", "Options", start = false, modifier = Modifier.align(Alignment.CenterEnd))

        BottomBlock(model, status, now, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun TopBug(channelNumber: String, status: PlaybackStatus) {
    Row(
        Modifier.padding(start = LiveWireDimens.SafeHorizontal, top = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (channelNumber.isNotBlank()) {
            Text(
                channelNumber,
                style = LiveWireType.Number,
                color = LiveWireColors.OnSurface,
                modifier = Modifier
                    .background(LiveWireColors.Canvas.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                    .border(1.dp, LiveWireColors.Border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        when {
            status.state == PlaybackState.PAUSED -> PlayerTag("❚❚ PAUSED")
            status.isLive -> LiveBadge(color = LiveWireColors.OnSurface)
        }
    }
}

/** A raised mono tag, e.g. "❚❚ PAUSED · 6 MIN BEHIND LIVE". */
@Composable
internal fun PlayerTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LiveWireType.Tag.copy(fontSize = 8.25.sp),
        color = LiveWireColors.OnSurface,
        modifier = modifier
            .background(LiveWireColors.SurfaceRaised, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** "9:24 PM" over the date, top right. */
@Composable
internal fun PlayerClock(now: Long, modifier: Modifier = Modifier) {
    Text(
        PlayerFormats.clockMeridiem(now),
        style = LiveWireType.Number.copy(fontSize = 13.5.sp),
        color = LiveWireColors.OnSurface,
        modifier = modifier.padding(end = LiveWireDimens.SafeHorizontal, top = 21.dp),
    )
}

@Composable
private fun EdgeHint(arrow: String, label: String, start: Boolean, modifier: Modifier) {
    Box(modifier.padding(horizontal = 22.dp)) {
        if (start) KeyHints(arrow to label) else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = LiveWireType.Hint, color = LiveWireColors.OnSurfaceMuted)
            Spacer(Modifier.width(5.dp))
            KeyHints(arrow to "")
        }
    }
}

@Composable
private fun BottomBlock(model: RestingOverlayModel, status: PlaybackStatus, now: Long, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = LiveWireDimens.SafeHorizontal, end = LiveWireDimens.SafeHorizontal, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            val channel = model.channel
            if (channel != null) {
                ChannelLogo(channel, Modifier.size(width = 48.dp, height = 30.dp))
            } else {
                Text(wordmark(model.channelName), style = LiveWireType.Title, color = LiveWireColors.OnSurface, maxLines = 1)
            }
            Column {
                Text(model.channelName, style = LiveWireType.Title, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subline = PlayerFormats.channelSubline(
                    channelNumber = model.channelNumber,
                    video = model.video,
                    audioChannelCount = model.audioChannelCount,
                    hasSubtitles = model.hasSubtitles,
                )
                if (subline.isNotBlank()) {
                    Text(subline.uppercase(), style = LiveWireType.Overline.copy(letterSpacing = LiveWireType.Overline.letterSpacing * 0.75f), color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        NowNext(model, now, failed = status.state == PlaybackState.ERROR, paused = status.state == PlaybackState.PAUSED)
    }
}

@Composable
private fun NowNext(model: RestingOverlayModel, now: Long, failed: Boolean, paused: Boolean) {
    val nowPlaying = model.nowPlaying
    val hints = arrayOf(
        "OK" to when { failed -> "Retry"; paused -> "Play"; else -> "Pause" },
        "▲▼" to "Channel",
        "▼" to "Seek",
    )
    if (nowPlaying == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline("On now")
            Text("No programme information", style = LiveWireType.Body, color = LiveWireColors.OnSurfaceMuted)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            KeyHints(*hints)
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        // A Live Now event shows here before it starts too, so say which it is.
        Overline(if (EventTimes.isUpcoming(nowPlaying, now)) "Up next" else "On now")
        Text(nowPlaying.title, style = LiveWireType.Display, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        nowPlaying.category?.takeIf { it.isNotBlank() }?.let {
            Text("· $it", style = LiveWireType.Body, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
        }
    }
    nowPlaying.description?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = LiveWireType.Body.copy(fontSize = 10.5.sp), color = LiveWireColors.OnSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 615.dp))
    }

    Spacer(Modifier.height(15.dp))
    if (nowPlaying.endIsEstimate) {
        // The end is the pool's guess: no end clock and no progress bar.
        Text(EventTimes.label(nowPlaying, now), style = LiveWireType.Mono, color = LiveWireColors.OnSurfaceMuted)
    } else {
        LiveWireProgress(nowPlaying.progressAt(now), Modifier.fillMaxWidth(), height = 3.dp, dot = 9.dp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(PlayerFormats.clockMeridiem(nowPlaying.startMs), style = LiveWireType.Mono, color = LiveWireColors.OnSurfaceMuted)
            Spacer(Modifier.weight(1f))
            Text(minutesLeftLabel(nowPlaying, now), style = LiveWireType.Mono, color = LiveWireColors.OnSurface)
            Spacer(Modifier.weight(1f))
            Text(PlayerFormats.clockMeridiem(nowPlaying.stopMs), style = LiveWireType.Mono, color = LiveWireColors.OnSurfaceMuted)
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        model.upNext?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                Text("UP NEXT", style = LiveWireType.Overline.copy(fontSize = 9.sp), color = LiveWireColors.OnSurfaceMuted)
                Text(PlayerFormats.clock(next.startMs), style = LiveWireType.Mono.copy(fontSize = 9.sp), color = LiveWireColors.OnSurface)
                Text(next.title, style = LiveWireType.Mono.copy(fontSize = 9.sp), color = LiveWireColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } ?: Spacer(Modifier.weight(1f))
        KeyHints(*hints)
    }
}

private fun minutesLeftLabel(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}
