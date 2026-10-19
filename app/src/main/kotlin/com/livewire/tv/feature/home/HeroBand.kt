package com.livewire.tv.feature.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EventTimes
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.PerformanceTier

/** What the hero band shows: the focused channel and its on-now / up-next programmes. */
data class HeroContent(
    val channel: LiveChannel,
    val nowPlaying: EpgProgramme?,
    val upNext: EpgProgramme?,
)

/**
 * Home hero band (design system section 9.1). Full content width, ~140dp tall, on
 * [LiveWireColors.Surface] with a STATIC brand tint (section 3.2) — no blur, no animated
 * gradient, no palette extraction. Left: a wordmark mark tile in the channel's brand colour.
 * Right: LIVE badge + channel name, ON NOW, the title, time · genre, the grey progress bar
 * with "N min elapsed / N min left", then UP NEXT. It is not focusable; the screen drives
 * [content] from whichever card holds focus, debounced.
 */
@Composable
fun HeroBand(content: HeroContent?, now: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    val tier = LiveWireTheme.tokens.tier
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceXl, vertical = LiveWireDimens.SpaceL),
    ) {
        if (content == null) return@Box
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroMark(content.channel)
            Spacer(Modifier.width(LiveWireDimens.SpaceXl))
            HeroBody(content, now, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroMark(channel: LiveChannel) {
    Box(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(LiveWireDimens.RadiusCard))
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusCard))
            .padding(LiveWireDimens.SpaceM),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logoUrl != null) {
            // Real provider logo when we have one (matches the card below it).
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Text wordmark fallback, kept inside the tile by the outer padding.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    wordmark(channel.name),
                    style = MaterialTheme.typography.displaySmall,
                    color = LiveWireTheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(LiveWireDimens.SpaceS))
                Text(
                    channel.name.uppercase(),
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeroBody(content: HeroContent, now: Long, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveBadge()
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
            Text(
                content.channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        val nowPlaying = content.nowPlaying
        Text(
            nowPlaying?.title ?: content.channel.name,
            style = MaterialTheme.typography.displaySmall,
            color = LiveWireTheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (nowPlaying != null && nowPlaying.endIsEstimate) {
            // A Live Now event: its end is a guess, so name the start (and sport) instead.
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(EventTimes.label(nowPlaying, now), nowPlaying.category).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (nowPlaying != null) {
            Spacer(Modifier.height(8.dp))
            LiveWireProgress(nowPlaying.progressAt(now), Modifier.fillMaxWidth(0.62f), height = 4.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                "${minutesElapsedLabel(nowPlaying, now)} · ${minutesLeftLabel(nowPlaying, now)}",
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            content.upNext?.let { next ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
                    Text("UP NEXT", style = LiveWireTheme.tokens.overline, color = LiveWireTheme.onSurfaceMuted)
                    Text(
                        next.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LiveWireTheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(LiveWireColors.Live)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", style = LiveWireTheme.tokens.tag, color = Color.White)
    }
}
