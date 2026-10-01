package com.livewire.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.favorites.ui.FavoriteStar
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireSurface

/**
 * A focusable live-channel card for the Home rails (design system section 9.2):
 * logo area on top, then name, what's on now, and a neutral progress bar with
 * "N min left". Focus styling comes from [LiveWireSurface].
 */
@Composable
fun ChannelCard(
    channel: LiveChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowPlaying: EpgProgramme? = null,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    LiveWireSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        restingColor = LiveWireColors.Surface,
        modifier = modifier.width(148.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(LiveWireColors.Canvas.copy(alpha = 0.35f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        wordmark(channel.name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = LiveWireColors.OnSurface,
                        maxLines = 1,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        FavoriteStar(size = 11.dp)
                    }
                }
                Text(
                    nowPlaying?.title ?: " ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                if (nowPlaying != null) {
                    val now = System.currentTimeMillis()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveWireProgress(nowPlaying.progressAt(now), Modifier.weight(1f))
                        Spacer(Modifier.size(LiveWireDimens.SpaceS))
                        Text(
                            minutesLeft(nowPlaying, now),
                            style = MaterialTheme.typography.labelMedium,
                            color = LiveWireColors.OnSurfaceMuted,
                        )
                    }
                } else {
                    Spacer(Modifier.height(13.dp))
                }
            }
        }
    }
}

/** "36 min left", or "ends soon" in the last minute. */
internal fun minutesLeft(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

/** Text fallback when a channel has no logo: strip "US - " style prefixes and quality tags. */
internal fun wordmark(name: String): String {
    val base = name.substringAfter(" - ", name)
        .replace(Regex("""\b(HD|FHD|UHD|SD|4K|HEVC)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*]"""), "")
        .trim()
    return base.split(' ').filter { it.isNotBlank() }.take(2).joinToString(" ").ifBlank { name.take(6) }
}
