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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.core.player.MediaInfo
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/** PlayerView aspect handling for the Picture row. Maps to AspectRatioFrameLayout resize modes. */
enum class PictureMode(val label: String) { FIT("Fit"), FILL("Fill"), ZOOM("Zoom") }

/** Which screen the Options panel is showing: the top-level list or a value sub-list. */
enum class OptionsView { ROOT, AUDIO, SUBTITLES, PICTURE, STREAM_INFO }

/**
 * Hoisted Options-panel navigation state, owned by the screen so its BackHandler can decide
 * whether Back returns to the root list (inside a sub-list) or closes the whole panel (at the
 * root). [onBack] returns true when it consumed Back by stepping up to the root.
 */
class OptionsPanelState {
    var view by mutableStateOf(OptionsView.ROOT)
        private set

    /** The sub-list last opened from the root, so returning to the root refocuses its row. */
    var lastOpened by mutableStateOf(OptionsView.AUDIO)
        private set

    fun open(v: OptionsView) { lastOpened = v; view = v }
    fun reset() { view = OptionsView.ROOT }

    /** Fresh open of the panel: root list, focus on the first row. */
    fun start() { lastOpened = OptionsView.AUDIO; view = OptionsView.ROOT }

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
 * by the screen via [OptionsPanelState]). Rows in first-PR scope: Audio, Subtitles, Picture,
 * Reload stream, Stream info. Favourites / Sleep timer / Stream format are deferred.
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
                OptionsView.AUDIO -> "AUDIO"
                OptionsView.SUBTITLES -> "SUBTITLES"
                OptionsView.PICTURE -> "PICTURE"
                OptionsView.STREAM_INFO -> "STREAM INFO"
            }
            Text(
                header,
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Box(Modifier.weight(1f)) {
                when (view) {
                    OptionsView.ROOT -> RootList(mediaInfo, pictureMode, rootRows, onReload, isFavorite, onToggleFavorite) { state.open(it) }
                    OptionsView.AUDIO -> AudioList(mediaInfo, firstRow) { onSelectAudio(it); state.reset() }
                    OptionsView.SUBTITLES -> SubtitlesList(mediaInfo, firstRow) { onSelectText(it); state.reset() }
                    OptionsView.PICTURE -> PictureList(pictureMode, firstRow) { onSelectPicture(it); state.reset() }
                    OptionsView.STREAM_INFO -> StreamInfoList(mediaInfo)
                }
            }
            HintRow(view)
        }
    }

    // Initial focus is the first row. Back to the root refocuses the row that opened the sub-list.
    androidx.compose.runtime.LaunchedEffect(view) {
        val target = if (view == OptionsView.ROOT) rootRows.getValue(state.lastOpened) else firstRow
        runCatching { target.requestFocus() }
    }
}

@Composable
private fun RootList(
    mediaInfo: MediaInfo,
    pictureMode: PictureMode,
    rows: Map<OptionsView, FocusRequester>,
    onReload: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpen: (OptionsView) -> Unit,
) {
    val audioValue = mediaInfo.audioTracks.firstOrNull { it.id == mediaInfo.selectedAudioId }?.summary
        ?: mediaInfo.audioTracks.firstOrNull()?.summary ?: "Default"
    val subtitleValue = mediaInfo.textTracks.firstOrNull { it.id == mediaInfo.selectedTextId }?.label ?: "Off"
    val streamInfoValue = PlayerFormats.streamInfoSummary(mediaInfo.video)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            OptionRow(
                iconRes = if (isFavorite) R.drawable.ic_fav_star_filled else R.drawable.ic_fav_star_outline,
                label = com.livewire.tv.feature.favorites.ui.FavoritesUi.favouriteActionLabel(isFavorite),
                value = null,
                hasChevron = false,
            ) { onToggleFavorite() }
        }
        item { Separator() }
        item {
            OptionRow(R.drawable.ic_opt_audio, "Audio", audioValue, hasChevron = true, focusRequester = rows.getValue(OptionsView.AUDIO)) {
                onOpen(OptionsView.AUDIO)
            }
        }
        item {
            OptionRow(R.drawable.ic_opt_subtitles, "Subtitles", subtitleValue, hasChevron = true, focusRequester = rows.getValue(OptionsView.SUBTITLES)) {
                onOpen(OptionsView.SUBTITLES)
            }
        }
        item {
            OptionRow(R.drawable.ic_opt_picture, "Picture", pictureMode.label, hasChevron = true, focusRequester = rows.getValue(OptionsView.PICTURE)) {
                onOpen(OptionsView.PICTURE)
            }
        }
        item { Separator() }
        item {
            OptionRow(R.drawable.ic_opt_reload, "Reload stream", value = null, hasChevron = false) { onReload() }
        }
        item {
            OptionRow(R.drawable.ic_opt_info, "Stream info", streamInfoValue.ifBlank { null }, hasChevron = true, focusRequester = rows.getValue(OptionsView.STREAM_INFO)) {
                onOpen(OptionsView.STREAM_INFO)
            }
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
                Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
                Spacer(Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
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
    onClick: () -> Unit,
) {
    PlayerFlatRow(
        onClick = onClick,
        modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
    ) {
        Row(
            // ~32 dp content ≈ 64 px at 1080p (mockup option rows).
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            Icon(painterResource(iconRes), contentDescription = null, tint = LiveWireColors.OnSurfaceMuted, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasChevron) {
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
                Text("›", style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurfaceMuted)
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
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
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
        color = LiveWireColors.OnSurfaceMuted,
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
            Text(key, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}
