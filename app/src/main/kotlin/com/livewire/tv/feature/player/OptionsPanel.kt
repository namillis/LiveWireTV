package com.livewire.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.core.player.MediaInfo
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.feature.providers.domain.PoolSource
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/** PlayerView aspect handling for the Picture row. Maps to AspectRatioFrameLayout resize modes. */
enum class PictureMode(val label: String) { FIT("Fit"), FILL("Fill"), ZOOM("Zoom") }

/** Which screen the Options panel is showing: the top-level list or a value sub-list. */
enum class OptionsView { ROOT, SOURCE, AUDIO, SUBTITLES, PICTURE, STREAM_INFO }

/**
 * Hoisted Options-panel navigation state, owned by the screen so its BackHandler can decide
 * whether Back returns to the root list (inside a sub-list) or closes the whole panel (at the
 * root). [onBack] returns true when it consumed Back by stepping up to the root.
 */
class OptionsPanelState {
    var view by mutableStateOf(OptionsView.ROOT)
        private set

    /**
     * The sub-list last opened from the root, so returning to the root refocuses its row. Null
     * means a fresh open (see [rootFocus]).
     */
    var lastOpened by mutableStateOf<OptionsView?>(null)
        private set

    fun open(v: OptionsView) { lastOpened = v; placed = null; userMoved = false; view = v }
    fun reset() { view = OptionsView.ROOT }

    /** Fresh open of the panel: root list, focus per [rootTarget]. */
    fun start() { lastOpened = null; placed = null; userMoved = false; view = OptionsView.ROOT }

    /** The root row the panel focused by itself; null until it has picked one. */
    private var placed: OptionsView? = null

    /** True once [placed] has actually taken focus. */
    private var landed = false

    /** True once the user moves focus off [placed] on the root list. */
    private var userMoved = false

    /**
     * Report a root row taking focus. [row] is null for rows that open no sub-list (Favourite,
     * Reload stream). Focus leaving [placed] after it landed there is the user moving.
     */
    fun onRootRowFocused(row: OptionsView?) {
        val p = placed ?: return
        if (row == p) landed = true else if (landed) userMoved = true
    }

    /**
     * The root row to focus now, or null to leave focus where the user put it.
     *
     * Back from a sub-list returns to the row that opened it. A fresh open focuses Source, not
     * Favourite, so a quick Right then OK can't toggle Favourite by accident. Source only shows
     * when the channel has two or more streams; otherwise the next row, Audio.
     *
     * The streams load after the panel opens, so [hasSourceRow] can turn true a moment later.
     * Focus then moves up to Source only if it is still on the row the panel picked.
     */
    fun rootTarget(hasSourceRow: Boolean): OptionsView? {
        if (userMoved) return null
        val target = lastOpened ?: if (hasSourceRow) OptionsView.SOURCE else OptionsView.AUDIO
        if (target != placed) { placed = target; landed = false }
        return target
    }

    /** Handle Back: true = stepped a sub-list up to root (consumed); false = already at root. */
    fun onBack(): Boolean {
        if (view == OptionsView.ROOT) return false
        view = OptionsView.ROOT
        return true
    }
}

@Composable
fun rememberOptionsPanelState(): OptionsPanelState = remember { OptionsPanelState() }

/**
 * The Options side panel (design system §9.6, mockup option3-options.png): a right-hand panel
 * with one up/down list, each row showing its current value. OK on a choice row opens a
 * sub-list in the same panel; Back returns to the root, Back again closes the panel (handled
 * by the screen via [OptionsPanelState]). Rows: Favourite (add/remove the current channel),
 * Audio, Subtitles, Picture, Reload stream, Stream info. Stream format lives in Settings; a
 * sleep timer is not built.
 *
 * All track changes go through [PlaybackEngine] via the callbacks; this panel never touches
 * ExoPlayer.
 */
@Composable
fun OptionsPanel(
    state: OptionsPanelState,
    mediaInfo: MediaInfo,
    pictureMode: PictureMode,
    onSelectAudio: (String) -> Unit,
    onSelectText: (String?) -> Unit,
    onSelectPicture: (PictureMode) -> Unit,
    onReload: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The channel's streams on the IPTV provider pool; the Source row shows when there are two or more. */
    sources: List<PoolSource> = emptyList(),
    onSelectSource: (String) -> Unit = {},
) {
    val view = state.view
    val firstRow = remember(view) { FocusRequester() }
    // Root rows that open a sub-list, so Back to the root lands on the row the user came from.
    val rootRows = remember { OptionsView.entries.associateWith { FocusRequester() } }

    Box(
        modifier
            .fillMaxHeight()
            .padding(top = LiveWireDimens.SafeVertical, bottom = LiveWireDimens.SafeVertical, end = LiveWireDimens.SafeHorizontal)
            .width(430.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LiveWireColors.Surface)
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceL),
    ) {
        Column(Modifier.fillMaxHeight()) {
            val header = when (view) {
                OptionsView.ROOT -> "OPTIONS"
                OptionsView.SOURCE -> "SOURCE"
                OptionsView.AUDIO -> "AUDIO"
                OptionsView.SUBTITLES -> "SUBTITLES"
                OptionsView.PICTURE -> "PICTURE"
                OptionsView.STREAM_INFO -> "STREAM INFO"
            }
            Text(
                header,
                style = LiveWireTheme.tokens.overline,
                color = LiveWireTheme.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Box(Modifier.weight(1f)) {
                when (view) {
                    OptionsView.ROOT -> RootList(mediaInfo, pictureMode, rootRows, state::onRootRowFocused, onReload, isFavorite, onToggleFavorite, sources) { state.open(it) }
                    OptionsView.SOURCE -> SourceList(sources, firstRow) { onSelectSource(it); state.reset() }
                    OptionsView.AUDIO -> AudioList(mediaInfo, firstRow) { onSelectAudio(it); state.reset() }
                    OptionsView.SUBTITLES -> SubtitlesList(mediaInfo, firstRow) { onSelectText(it); state.reset() }
                    OptionsView.PICTURE -> PictureList(pictureMode, firstRow) { onSelectPicture(it); state.reset() }
                    OptionsView.STREAM_INFO -> StreamInfoList(mediaInfo)
                }
            }
            HintRow(view)
        }
    }

    // A sub-list focuses its first row; the root focuses the row from [OptionsPanelState.rootTarget].
    // The rows live in a LazyColumn, which composes them during its first layout, a frame or more
    // after this effect starts. Focusing before then silently did nothing, so the panel opened
    // with no row selected and D-pad/OK went to a row the user couldn't see. The effect reruns
    // when the Source row appears, since the channel's streams load after the panel opens.
    val rootHasSource = view == OptionsView.ROOT && hasSourceRow(sources)
    androidx.compose.runtime.LaunchedEffect(view, rootHasSource) {
        val target = when (view) {
            OptionsView.ROOT -> state.rootTarget(rootHasSource)?.let(rootRows::getValue) ?: return@LaunchedEffect
            else -> firstRow
        }
        target.requestFocusOnceAttached()
    }
}

/**
 * Focus this requester once its row is laid out, retrying for up to [FOCUS_ATTEMPTS] frames.
 * `requestFocus(Enter)` returns false (older Compose throws) while no node is attached yet.
 */
private suspend fun FocusRequester.requestFocusOnceAttached() {
    repeat(FOCUS_ATTEMPTS) {
        androidx.compose.runtime.withFrameNanos { }
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return
    }
}

/** About half a second at 60 fps: past the panel's slide-in, so a row that never appears gives up. */
private const val FOCUS_ATTEMPTS = 30

/** The root list shows a Source row only when the channel has more than one stream to pick. */
private fun hasSourceRow(sources: List<PoolSource>): Boolean = sources.size > 1

@Composable
private fun RootList(
    mediaInfo: MediaInfo,
    pictureMode: PictureMode,
    rows: Map<OptionsView, FocusRequester>,
    onRowFocused: (OptionsView?) -> Unit,
    onReload: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    sources: List<PoolSource>,
    onOpen: (OptionsView) -> Unit,
) {
    val audioValue = mediaInfo.audioTracks.firstOrNull { it.id == mediaInfo.selectedAudioId }?.summary
        ?: mediaInfo.audioTracks.firstOrNull()?.summary ?: "Default"
    val subtitleValue = mediaInfo.textTracks.firstOrNull { it.id == mediaInfo.selectedTextId }?.label ?: "Off"
    val streamInfoValue = PlayerFormats.streamInfoSummary(mediaInfo.video)

    // Keyed items: when the Source row appears above a focused row, that row keeps its focus.
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "favourite") {
            OptionRow(
                iconRes = if (isFavorite) R.drawable.ic_fav_star_filled else R.drawable.ic_fav_star_outline,
                label = com.livewire.tv.feature.favorites.ui.FavoritesUi.favouriteActionLabel(isFavorite),
                value = null,
                hasChevron = false,
                onFocused = { onRowFocused(null) },
            ) { onToggleFavorite() }
        }
        item(key = "separator-top") { Separator() }
        if (hasSourceRow(sources)) {
            item(key = OptionsView.SOURCE) {
                val current = sources.firstOrNull { it.current }?.label ?: "Automatic"
                OptionRow(R.drawable.ic_opt_source, "Source", current, hasChevron = true, focusRequester = rows.getValue(OptionsView.SOURCE), onFocused = { onRowFocused(OptionsView.SOURCE) }) {
                    onOpen(OptionsView.SOURCE)
                }
            }
        }
        item(key = OptionsView.AUDIO) {
            OptionRow(R.drawable.ic_opt_audio, "Audio", audioValue, hasChevron = true, focusRequester = rows.getValue(OptionsView.AUDIO), onFocused = { onRowFocused(OptionsView.AUDIO) }) {
                onOpen(OptionsView.AUDIO)
            }
        }
        item(key = OptionsView.SUBTITLES) {
            OptionRow(R.drawable.ic_opt_subtitles, "Subtitles", subtitleValue, hasChevron = true, focusRequester = rows.getValue(OptionsView.SUBTITLES), onFocused = { onRowFocused(OptionsView.SUBTITLES) }) {
                onOpen(OptionsView.SUBTITLES)
            }
        }
        item(key = OptionsView.PICTURE) {
            OptionRow(R.drawable.ic_opt_picture, "Picture", pictureMode.label, hasChevron = true, focusRequester = rows.getValue(OptionsView.PICTURE), onFocused = { onRowFocused(OptionsView.PICTURE) }) {
                onOpen(OptionsView.PICTURE)
            }
        }
        item(key = "separator-bottom") { Separator() }
        item(key = "reload") {
            OptionRow(R.drawable.ic_opt_reload, "Reload stream", value = null, hasChevron = false, onFocused = { onRowFocused(null) }) { onReload() }
        }
        item(key = OptionsView.STREAM_INFO) {
            OptionRow(R.drawable.ic_opt_info, "Stream info", streamInfoValue.ifBlank { null }, hasChevron = true, focusRequester = rows.getValue(OptionsView.STREAM_INFO), onFocused = { onRowFocused(OptionsView.STREAM_INFO) }) {
                onOpen(OptionsView.STREAM_INFO)
            }
        }
    }
}

@Composable
private fun SourceList(sources: List<PoolSource>, firstRow: FocusRequester, onPick: (String) -> Unit) {
    if (sources.isEmpty()) {
        EmptyNote("No other sources for this channel")
        return
    }
    val repeated = sources.groupingBy { it.label }.eachCount()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(sources, key = { it.key }) { s ->
            // Two streams can share a label ("Teck · UHD" for both UHD and UHD+); the provider's own
            // channel name tells them apart.
            val base = if ((repeated[s.label] ?: 0) > 1 && s.channelName.isNotBlank()) "${s.label} · ${s.channelName}" else s.label
            ChoiceRow(
                label = if (s.failed) "$base · Failed" else base,
                selected = s.current,
                focusRequester = if (s.key == sources.first().key) firstRow else null,
            ) { onPick(s.key) }
        }
    }
}

@Composable
private fun AudioList(mediaInfo: MediaInfo, firstRow: FocusRequester, onPick: (String) -> Unit) {
    val tracks = mediaInfo.audioTracks
    if (tracks.isEmpty()) {
        EmptyNote("No alternate audio tracks")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tracks, key = { it.id }) { track ->
            ChoiceRow(
                label = track.summary,
                selected = track.id == mediaInfo.selectedAudioId,
                focusRequester = if (track.id == tracks.first().id) firstRow else null,
            ) { onPick(track.id) }
        }
    }
}

@Composable
private fun SubtitlesList(mediaInfo: MediaInfo, firstRow: FocusRequester, onPick: (String?) -> Unit) {
    val tracks = mediaInfo.textTracks
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // "Off" is always the first choice; subtitles default to off (requirement 2).
        item {
            ChoiceRow(label = "Off", selected = mediaInfo.selectedTextId == null, focusRequester = firstRow) { onPick(null) }
        }
        items(tracks, key = { it.id }) { track ->
            ChoiceRow(label = track.label, selected = track.id == mediaInfo.selectedTextId) { onPick(track.id) }
        }
        if (tracks.isEmpty()) item { EmptyNote("This channel has no subtitle tracks") }
    }
}

@Composable
private fun PictureList(current: PictureMode, firstRow: FocusRequester, onPick: (PictureMode) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(PictureMode.entries.toList(), key = { it.name }) { mode ->
            ChoiceRow(
                label = mode.label,
                selected = mode == current,
                focusRequester = if (mode == PictureMode.entries.first()) firstRow else null,
            ) { onPick(mode) }
        }
    }
}

@Composable
private fun StreamInfoList(mediaInfo: MediaInfo) {
    val rows = PlayerFormats.streamInfoRows(mediaInfo.video)
    if (rows.isEmpty()) {
        EmptyNote("Stream details are not available yet")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows) { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireTheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.labelMedium, color = LiveWireTheme.onSurfaceMuted)
            }
        }
    }
}

/** A top-level options row: icon, label, current value (optional), chevron (optional). */
@Composable
private fun OptionRow(
    iconRes: Int,
    label: String,
    value: String?,
    hasChevron: Boolean,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    PlayerFlatRow(
        onClick = onClick,
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            // ~32 dp content ≈ 64 px at 1080p (mockup option rows).
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            Icon(painterResource(iconRes), contentDescription = null, tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireTheme.onSurface, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelMedium, color = LiveWireTheme.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasChevron) {
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
                Text("›", style = MaterialTheme.typography.titleMedium, color = LiveWireTheme.onSurfaceMuted)
            }
        }
    }
}

/** A sub-list choice row with a check mark when it is the current selection. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    PlayerFlatRow(
        onClick = onClick,
        modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireTheme.onSurface, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = LiveWireTheme.onSurface)
        }
    }
}

@Composable
private fun Separator() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS)
            .height(1.dp).background(LiveWireColors.Border),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LiveWireTheme.onSurfaceMuted,
        modifier = Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceM),
    )
}

@Composable
private fun HintRow(view: OptionsView) {
    val closeLabel = if (view == OptionsView.ROOT) "Close" else "Back"
    // Stream info is read-only: OK does nothing there, so show only the Back hint (item 8).
    val showChange = view != OptionsView.STREAM_INFO
    Row(
        Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
    ) {
        if (showChange) KeyHint("OK", "Change")
        KeyHint("◀ BACK", closeLabel)
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).background(LiveWireColors.SurfaceRaised).padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(key, style = LiveWireTheme.tokens.tag, color = LiveWireTheme.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireTheme.onSurfaceMuted)
    }
}
