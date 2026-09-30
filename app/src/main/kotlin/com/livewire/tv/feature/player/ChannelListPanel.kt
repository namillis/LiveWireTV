package com.livewire.tv.feature.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireTheme

/**
 * The Channels side panel (mockup option3-channels.png): a left-hand panel listing the
 * channels in the PLAYING channel's category — number, name, now-playing + progress — with
 * the playing one tagged WATCHING. Focus starts on the playing channel. A right-side preview,
 * anchored to the right safe edge so it never overlaps the panel, shows the focused channel's
 * programme (title, time · category · min left, description). OK switches channel in place;
 * Back/▶ closes (handled by the screen).
 *
 * Rows are flat ([PlayerFlatRow]): no fill or border at rest, only the focused row is raised
 * with the amber ring. The panel sits inside the 48 dp horizontal safe area so the focused
 * ring and 1.02 scale are never clipped.
 */
@Composable
fun ChannelListPanel(
    state: ChannelListState,
    now: Long,
    onPlay: (ChannelListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val playingIndex = state.playingIndex.coerceAtLeast(0)
        var focusedIndex by remember(state.items) { mutableIntStateOf(playingIndex) }
        val listState = rememberLazyListState()
        val initialFocus = remember(state.items) { FocusRequester() }

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = LiveWireDimens.SafeHorizontal, top = LiveWireDimens.SafeVertical, bottom = LiveWireDimens.SafeVertical)
                .width(LiveWireDimens.SafeHorizontal + 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LiveWireColors.Surface)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceL),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceS, vertical = LiveWireDimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.categoryName.uppercase(), style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
                Spacer(Modifier.weight(1f))
                Text("${state.items.size} channels", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(state.items, key = { _, it -> it.channel.streamId }) { index, item ->
                    ChannelRow(
                        item = item,
                        watching = index == state.playingIndex,
                        now = now,
                        focusRequester = if (index == playingIndex) initialFocus else null,
                        onFocused = { focusedIndex = index },
                        onClick = { onPlay(item) },
                    )
                }
            }
            HintRow()
        }

        // Right-side preview, anchored to the right safe edge (never over the left panel).
        state.items.getOrNull(focusedIndex)?.let { focused ->
            Preview(
                item = focused,
                now = now,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical)
                    .width(380.dp),
            )
        }

        androidx.compose.runtime.LaunchedEffect(state.items) {
            if (state.items.isNotEmpty()) {
                runCatching {
                    listState.scrollToItem(playingIndex)
                    initialFocus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    item: ChannelListItem,
    watching: Boolean,
    now: Long,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    PlayerFlatRow(
        onClick = onClick,
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            // ~43 dp content ≈ 86 px at 1080p, so ~6 channels fit (mockup).
            Modifier.fillMaxWidth().heightIn(min = 43.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            Text(
                item.number,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.width(34.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
                    Text(
                        item.channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = LiveWireColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (watching) WatchingTag()
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    programmeLine(item.nowPlaying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.nowPlaying != null) {
                    Spacer(Modifier.height(5.dp))
                    LiveWireProgress(item.nowPlaying.progressAt(now), Modifier.fillMaxWidth(), height = 3.dp)
                }
            }
        }
    }
}

@Composable
private fun WatchingTag() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LiveWireColors.SurfaceRaised)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("WATCHING", style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurfaceMuted)
    }
}

@Composable
private fun Preview(item: ChannelListItem, now: Long, modifier: Modifier) {
    // Own backing card: the video behind is dimmed only 55%, and broadcasters' white
    // lower-thirds still bleed through plain text at that level.
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LiveWireColors.Surface.copy(alpha = 0.94f))
            .padding(LiveWireDimens.SpaceL),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("PREVIEW · CH ${item.number}", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            item.nowPlaying?.title ?: item.channel.name,
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = previewMeta(item.nowPlaying, now)
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(meta, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
        }
        item.nowPlaying?.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HintRow() {
    Row(
        Modifier.padding(horizontal = LiveWireDimens.SpaceS, vertical = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
    ) {
        KeyHint("OK", "Watch")
        KeyHint("▶ BACK", "Close list")
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(LiveWireColors.SurfaceRaised).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(key, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** The row's programme line: "Hannity · 8:00–9:00 PM", the title alone, or the no-info note. */
private fun programmeLine(p: EpgProgramme?): String {
    if (p == null) return "No programme information"
    val range = PlayerFormats.timeRange(p.startMs, p.stopMs)
    return if (range.isBlank()) p.title else "${p.title} · $range"
}

/** Preview meta line: "8:00–9:00 PM · News · 36 min left", dropping unknown parts. */
private fun previewMeta(p: EpgProgramme?, now: Long): String {
    if (p == null) return ""
    return listOf(
        PlayerFormats.timeRange(p.startMs, p.stopMs),
        p.category.orEmpty(),
        PlayerFormats.minutesLeft(p.stopMs, now),
    ).filter { it.isNotBlank() }.joinToString(" · ")
}
