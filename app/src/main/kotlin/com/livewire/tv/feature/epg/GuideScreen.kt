package com.livewire.tv.feature.epg

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.livewire.tv.R
import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveBadge
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.LocalOnFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.feature.epg.domain.CatchupGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EventTimes
import com.livewire.tv.feature.epg.domain.GuidePastCellStyle
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PX_PER_MINUTE = 4          // 4dp per minute = 120dp per 30 min (B · Grid: about 2.8 h on screen)
private val ROW_HEIGHT = 41.dp               // 55px in the mockup; 9 rows fit under the band
private val CHANNEL_COL = 180.dp            // number, logo and name
/** Narrowest text area a cell keeps when its text follows the lane's left edge (stickyTextShift). */
private val STICKY_TEXT_MIN = 48.dp
private val SLOT = (30 * PX_PER_MINUTE).dp   // one 30-minute tick
private val NOW_LANE = 20.dp
private val AXIS_HEIGHT = 26.dp
private val DETAILS_BAND_HEIGHT = 46.dp      // one line: badge, channel, title, time, hints

/**
 * The focused guide cell: which channel + programme drives the details band. [channelHasArchive]
 * and [programmeArchived] carry the catch-up context so the band can offer the right action
 * (Watch from start / Watch live + Start over) without re-deriving it.
 */
private data class GuideFocus(
    val channel: LiveChannel,
    val programme: EpgProgramme?,
    val channelHasArchive: Boolean = false,
    val programmeArchived: Boolean = false,
)

/**
 * Full-screen EPG guide (design system §9.4). A details band for the focused programme leads
 * the screen; below it a time-axis header with a red NOW-line, then one horizontally-scrolling
 * lane of programme cells per channel. Rows start near now (GuideViewModel). Amber appears only
 * on the focused cell (via LiveWireSurface); red only on the now-line and NOW pill.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    onPlayChannel: (target: PlaybackTarget, title: String, fromFavorites: Boolean) -> Unit,
    onPlayArchive: (target: PlaybackTarget, title: String, startMs: Long, endMs: Long, programme: String) -> Unit =
        { _, _, _, _, _ -> },
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Re-runs on every return to the Guide; the ViewModel keeps the grid when it is still
    // fresh, so coming back from the player does not reload it or lose the user's place.
    LaunchedEffect(Unit) { viewModel.load() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "Loading the guide from your provider. Large guides can take a while.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, modifier = Modifier.padding(24.dp))
            }
            state.allRows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No guide data for this provider.")
            }
            else -> GuideContent(
                state = state,
                initialPosition = viewModel.position,
                onPositionChange = { viewModel.position = it },
                onSelectCategory = viewModel::setCategory,
                onTogglePin = viewModel::togglePin,
                onQueryChange = viewModel::setQuery,
                onPlayChannel = onPlayChannel,
                onPlayArchive = onPlayArchive,
                onRowShown = viewModel::requestPastListings,
                targetFor = viewModel::playbackTarget,
                resolvePreview = viewModel::previewSource,
                onToggleFavorite = viewModel::toggleFavorite,
                channelInfoFor = viewModel::channelInfoFor,
            )
        }
    }
}

@Composable
private fun GuideContent(
    state: GuideUiState,
    initialPosition: GuidePosition,
    onPositionChange: (GuidePosition) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onTogglePin: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onPlayChannel: (PlaybackTarget, String, Boolean) -> Unit,
    onPlayArchive: (PlaybackTarget, String, Long, Long, String) -> Unit,
    onRowShown: (String) -> Unit,
    targetFor: (LiveChannel) -> PlaybackTarget?,
    resolvePreview: suspend (String) -> PlaybackSource?,
    onToggleFavorite: (LiveChannel) -> Unit,
    channelInfoFor: (LiveChannel) -> com.livewire.tv.feature.favorites.ui.ChannelInfoModel,
) {
    val now = System.currentTimeMillis()
    val scope = rememberCoroutineScope()
    val rows = state.rows
    // Focus moves run a frame or two later (after a filter changes the list), so they must
    // read the rows as they are then, not as they were when the move was requested.
    val latestRows by rememberUpdatedState(rows)
    // Both scroll positions start where the user left them (the ViewModel outlives the trip
    // to the player; this composable does not).
    val hScroll = rememberScrollState(initialPosition.horizontalScroll)
    val listState = rememberLazyListState(initialPosition.listIndex, initialPosition.listOffset)
    val spanMinutes = TimeUnit.MILLISECONDS.toMinutes(state.windowSpanMs).toInt()
    val laneWidth = minutesToDp(spanMinutes)

    // The time the user is browsing. Up/Down keep it; Left/Right set it from the cell they
    // land on (now while that cell is on air). Each row focuses the cell under it, so moving
    // down the live column over an empty row or a long programme stays in the live column.
    var anchorMs by remember { mutableLongStateOf(initialPosition.anchorMs ?: now) }
    var focusedStreamId by remember { mutableStateOf(initialPosition.focusedStreamId) }
    // Set just before we move focus ourselves, so the landing cell does not reset the anchor.
    var programmaticMove by remember { mutableStateOf(false) }
    // How the last guide-driven focus move happened, read by the lanes' bring-into-view.
    val laneMode = remember { LaneMode() }
    // The category column, which Left from a row's first cell opens.
    val columnRequester = remember { FocusRequester() }

    // True while a programme cell holds focus; the category column and drawer do not count.
    var gridFocused by remember { mutableStateOf(false) }
    // The channel whose hold-OK menu is open, or null when closed.
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // The channel whose Channel info panel is open, or null when closed.
    var infoChannel by remember { mutableStateOf<LiveChannel?>(null) }
    val preview = rememberGuidePreview(
        streamId = focusedStreamId.takeIf { gridFocused },
        enabled = state.previewEnabled,
        resolve = resolvePreview,
    )

    // Focus handle per row, attached to that row's anchor cell.
    val rowRequesters = remember { HashMap<String, FocusRequester>() }

    fun savePosition() {
        onPositionChange(
            GuidePosition(
                focusedStreamId = focusedStreamId,
                anchorMs = anchorMs,
                listIndex = listState.firstVisibleItemIndex,
                listOffset = listState.firstVisibleItemScrollOffset,
                horizontalScroll = hScroll.value,
            ),
        )
    }
    DisposableEffect(Unit) { onDispose { savePosition() } }

    /**
     * Focus [rowIndex]'s cell at the anchor, scrolling it into the list first if needed.
     * [stepping] is true for a Left/Right move (see [LaneMode]).
     */
    fun focusRow(rowIndex: Int, stepping: Boolean = false): Boolean {
        if (rowIndex !in latestRows.indices) return false
        laneMode.stepping = stepping
        scope.launch {
            val row = latestRows.getOrNull(rowIndex) ?: return@launch
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
            if (!visible) {
                listState.scrollToItem(rowIndex)
                withFrameNanos { }
            } else if (stepping) {
                // A Left/Right step changed the anchor: let the row recompose so its focus
                // handle sits on the neighbouring cell before we request focus.
                withFrameNanos { }
            }
            programmaticMove = true
            if (runCatching { rowRequesters[row.channel.streamId]?.requestFocus() }.isFailure) {
                programmaticMove = false
            }
        }
        return true
    }

    /**
     * Return focus to the row for [streamId] after an overlay (menu / Channel info) closes.
     * Resolved by stream id, not index, because toggling a favourite under the ★ Favourites
     * filter adds/removes rows; if that row is gone (unfavourited while filtered) fall back to
     * the previously focused row, then the first row. Retries a few frames so the restore lands
     * after the filtered list recomposes, otherwise focus falls to the nav drawer and opens it.
     */
    fun restoreFocusToStreamId(streamId: String?) {
        laneMode.stepping = false
        scope.launch {
            repeat(40) {
                val target = latestRows.indexOfFirst { it.channel.streamId == streamId }
                    .takeIf { it >= 0 }
                    ?: latestRows.indexOfFirst { it.channel.streamId == focusedStreamId }.takeIf { it >= 0 }
                    ?: 0
                if (target in latestRows.indices) {
                    val row = latestRows[target]
                    val requester = rowRequesters[row.channel.streamId]
                    if (requester != null) {
                        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
                        if (!visible) { listState.scrollToItem(target); withFrameNanos { } }
                        programmaticMove = true
                        if (runCatching { requester.requestFocus() }.isSuccess) return@launch
                        programmaticMove = false
                    }
                }
                delay(50)
            }
        }
    }

    // The details band follows the focused cell; before anything is focused it shows the
    // restored (or first) row's programme at the anchor.
    val restoreIndex = rows.indexOfFirst { it.channel.streamId == focusedStreamId }.coerceAtLeast(0)
    var focus by remember(rows) {
        val r = rows.getOrNull(restoreIndex)
        mutableStateOf(
            r?.let { row ->
                val prog = row.programmes.firstOrNull { p -> p.airsAt(anchorMs) }
                GuideFocus(
                    channel = row.channel,
                    programme = prog,
                    channelHasArchive = row.channel.hasArchive,
                    programmeArchived = prog != null && row.isArchived(prog),
                )
            },
        )
    }
    // The two band actions (Watch live / Start over) shown after OK on the now programme of an
    // archive channel. False restores the plain band with its hint chip.
    var bandActions by remember { mutableStateOf(false) }

    // On open (and on return), land the ring on the restored row -- the first row on a fresh
    // open -- at the anchor time. Wait for programmes so it lands on a real cell, and never
    // move focus the user has already moved.
    // A fresh open with a long past window would start at its far left (days ago). Scroll so
    // the lane starts 30 min before now; Back-to-Guide keeps the saved scroll instead.
    val density = LocalDensity.current
    var initialScrollDone by remember { mutableStateOf(initialPosition.horizontalScroll != 0) }
    LaunchedEffect(state.windowStartMs) {
        if (!initialScrollDone && state.windowStartMs > 0L) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - state.windowStartMs).toInt() - 30
            if (minutes > 0) hScroll.scrollTo(with(density) { minutesToDp(minutes).roundToPx() })
            initialScrollDone = true
        }
    }
    var focusPlaced by remember { mutableStateOf(false) }
    var userMovedFocus by remember { mutableStateOf(false) }
    val restoreHasCells = rows.getOrNull(restoreIndex)?.programmes?.isNotEmpty() == true
    LaunchedEffect(rows.isNotEmpty(), restoreHasCells || state.programmesLoaded, userMovedFocus) {
        if (rows.isNotEmpty() && (restoreHasCells || state.programmesLoaded) && !focusPlaced && !userMovedFocus) {
            withFrameNanos { }
            focusRow(restoreIndex)
            focusPlaced = true
        }
    }

    // A new filter shows a new list: start it at the top. (Skip the first run, which is the
    // screen opening with its restored scroll position.)
    var filterSeen by remember { mutableStateOf(false) }
    LaunchedEffect(state.categoryId, state.query) {
        if (filterSeen) {
            listState.scrollToItem(0)
            focusedStreamId = null
        }
        filterSeen = true
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = LiveWireDimens.SafeHorizontal,
                end = LiveWireDimens.SafeHorizontal,
                top = 6.dp,
                bottom = LiveWireDimens.SafeVertical,
            ),
    ) {
        focus?.let {
            GuideDetailsBand(
                focus = it,
                now = now,
                preview = preview,
                bandActions = bandActions,
                onWatchLive = {
                    preview.player.stop()
                    savePosition()
                    targetFor(it.channel)?.let { t -> onPlayChannel(t, it.channel.name, it.channel.streamId in state.favoriteIds) }
                },
                onStartOver = {
                    val prog = it.programme
                    val target = targetFor(it.channel)
                    if (prog != null && target != null) {
                        preview.player.stop()
                        savePosition()
                        onPlayArchive(target, it.channel.name, prog.startMs, prog.stopMs, prog.title)
                    }
                },
                onDismissActions = {
                    bandActions = false
                    val back = rows.indexOfFirst { r -> r.channel.streamId == focusedStreamId }
                    focusRow(if (back >= 0) back else 0)
                },
            )
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceS))

        Row(Modifier.fillMaxSize()) {
            GuideCategoryColumn(
                focusRequester = columnRequester,
                categories = state.columnCategories,
                totalChannels = state.totalChannels,
                selectedId = state.categoryId,
                pinnedIds = state.pinnedIds,
                onTogglePin = onTogglePin,
                query = state.query,
                onSelect = { id ->
                    onSelectCategory(id)
                    // Choosing a category goes straight to its channels, at the live column.
                    anchorMs = now
                    scope.launch {
                        withFrameNanos { }
                        withFrameNanos { }
                        focusRow(0)
                    }
                },
                onQueryChange = onQueryChange,
                gridEntry = {
                    val id = focusedStreamId?.takeIf { id -> listState.layoutInfo.visibleItemsInfo.any { it.key == id } }
                        ?: listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                    id?.let { rowRequesters[it] }
                },
                onExitRight = {
                    val back = rows.indexOfFirst { it.channel.streamId == focusedStreamId }
                    focusRow(if (back >= 0) back else listState.firstVisibleItemIndex)
                },
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))

            // The grid: NOW lane + axis header, then the scrolling rows, with the red now-line
            // overlaid across the lane area.
            val nowMin = nowLineMinutes(now, state.windowStartMs, state.windowSpanMs)
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    NowLane(nowMin, { hScroll.value }, now, state.windowStartMs)
                    AxisRow(state.windowStartMs, spanMinutes) { hScroll.value }
                    if (rows.isEmpty()) {
                        if (state.favoritesEmpty) FavoritesGuideEmpty() else NoMatches(state.query, state.categoryName)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { gridFocused = it.hasFocus }
                                // Up/Down move one row and keep the anchor time. Default focus
                                // search would pick the cell nearest the focused cell's centre,
                                // which for a wide cell (an empty row, a long film) is hours away.
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val current = rows.indexOfFirst { it.channel.streamId == focusedStreamId }
                                    if (current < 0) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> { focusRow(current + 1); true }
                                        Key.DirectionUp -> { focusRow(current - 1); true }
                                        // Left/Right step to the neighbouring programme through
                                        // the anchor (see [neighbourAnchor]). Left from a row's
                                        // first cell (or an empty row) opens the category column;
                                        // the platform's search cannot, because the cell's left
                                        // edge may be scrolled far off screen.
                                        Key.DirectionLeft, Key.DirectionRight -> {
                                            val forward = event.key == Key.DirectionRight
                                            val row = rows[current]
                                            val focusedProgramme = focus?.takeIf { it.channel.streamId == focusedStreamId }?.programme
                                            val target = focusedProgramme?.let {
                                                neighbourAnchor(
                                                    cells = laneCells(row.programmes, state.windowStartMs, state.windowSpanMs),
                                                    focused = it,
                                                    forward = forward,
                                                    nowMs = now,
                                                    windowStartMs = state.windowStartMs,
                                                )
                                            }
                                            when {
                                                target != null -> {
                                                    anchorMs = target
                                                    focusRow(current, stepping = true)
                                                }
                                                !forward -> runCatching { columnRequester.requestFocus() }
                                            }
                                            // Right past the last cell stays put rather than
                                            // escaping the grid.
                                            true
                                        }
                                        // Rewind / fast-forward (or Page Up/Down) jump the browsed
                                        // time 3 hours, so 2 days of archive is ~16 presses, not ~50
                                        // Lefts. Left/Right keep their normal meaning.
                                        Key.MediaRewind, Key.PageUp, Key.MediaFastForward, Key.PageDown -> {
                                            val back = event.key == Key.MediaRewind || event.key == Key.PageUp
                                            val target = (anchorMs + if (back) -GUIDE_JUMP_MS else GUIDE_JUMP_MS)
                                                .coerceIn(state.windowStartMs, state.windowStartMs + state.windowSpanMs - 1)
                                            anchorMs = target
                                            scope.launch {
                                                val minutes = ((target - state.windowStartMs) / 60_000L - 30L).coerceAtLeast(0L).toInt()
                                                hScroll.scrollTo(with(density) { minutesToDp(minutes).roundToPx() })
                                                withFrameNanos { }
                                                focusRow(current)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                        ) {
                            items(rows, key = { it.channel.streamId }) { row ->
                                val requester = remember { FocusRequester() }
                                DisposableEffect(row.channel.streamId) {
                                    rowRequesters[row.channel.streamId] = requester
                                    onDispose { if (rowRequesters[row.channel.streamId] === requester) rowRequesters.remove(row.channel.streamId) }
                                }
                                // Past listings load only for archive rows that come on screen.
                                if (row.channel.hasArchive) {
                                    LaunchedEffect(row.channel.streamId) { onRowShown(row.channel.streamId) }
                                }
                                GuideRowView(
                                    row = row,
                                    windowStartMs = state.windowStartMs,
                                    windowSpanMs = state.windowSpanMs,
                                    now = now,
                                    hScroll = hScroll,
                                    laneWidth = laneWidth,
                                    spanMinutes = spanMinutes,
                                    anchorMs = anchorMs,
                                    anchorRequester = requester,
                                    onFocus = { prog ->
                                        focus = GuideFocus(
                                            channel = row.channel,
                                            programme = prog,
                                            channelHasArchive = row.channel.hasArchive,
                                            programmeArchived = prog != null && row.isArchived(prog),
                                        )
                                        focusedStreamId = row.channel.streamId
                                        // Moving focus leaves the band's action mode.
                                        bandActions = false
                                        if (programmaticMove) {
                                            programmaticMove = false
                                        } else {
                                            // A Left/Right move (or entering from the column):
                                            // the landing cell sets the time being browsed.
                                            anchorForCell(prog, now, state.windowStartMs)?.let { anchorMs = it }
                                            if (!focusPlaced && isUserFocusMove(prog, state.programmesLoaded)) userMovedFocus = true
                                        }
                                        savePosition()
                                    },
                                    onClick = { prog ->
                                        val target = targetFor(row.channel) ?: return@GuideRowView
                                        val archivedPast = prog != null && prog.stopMs <= now && row.isArchived(prog)
                                        val onNowArchive = prog != null && prog.airsAt(now) && row.channel.hasArchive
                                        when {
                                            // Archived past programme: play it from its start (catch-up).
                                            archivedPast -> {
                                                preview.player.stop()
                                                savePosition()
                                                onPlayArchive(target, row.channel.name, prog!!.startMs, prog.stopMs, prog.title)
                                            }
                                            // Now programme on an archive channel: open the band's
                                            // Watch live / Start over actions (mockup option2-now).
                                            onNowArchive -> bandActions = true
                                            // Everything else (no-archive, future, no EPG): play live.
                                            else -> {
                                                preview.player.stop()
                                                savePosition()
                                                onPlayChannel(target, row.channel.name, row.channel.streamId in state.favoriteIds)
                                            }
                                        }
                                    },
                                    isFavorite = row.channel.streamId in state.favoriteIds,
                                    onLongClick = { menuChannel = row.channel },
                                    laneStepping = { laneMode.stepping },
                                )
                            }
                        }
                    }
                }
                // Red now-line, drawn above the cells, below the header lanes. It lives in a
                // viewport that starts at the channel column and clips to it, so it never crosses
                // into the channel column or the axis header; it hides once now scrolls off the left.
                if (nowMin != null && rows.isNotEmpty()) {
                    NowLine(nowMin, { hScroll.value }, topInset = NOW_LANE + AXIS_HEIGHT)
                }
            }
        }
    }

        // Hold-OK channel menu (mockup option2): dims the screen, Favourite focused by default.
        menuChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelMenuOverlay(
                channel = channel,
                isFavourite = channel.streamId in state.favoriteIds,
                onToggleFavourite = { onToggleFavorite(channel) },
                onPlay = {
                    targetFor(channel)?.let { onPlayChannel(it, channel.name, channel.streamId in state.favoriteIds) }
                },
                // The Channel info panel (Option A) opens from here. Close the menu as the panel
                // takes over; no row-focus restore (the panel owns focus).
                onChannelInfo = { infoChannel = channel; menuChannel = null },
                onDismiss = { menuChannel = null; restoreFocusToStreamId(channel.streamId) },
            )
        }

        // Channel info panel (Option A) over a strongly dimmed Guide.
        infoChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelInfoPanel(
                model = channelInfoFor(channel),
                now = System.currentTimeMillis(),
                onPlay = {
                    targetFor(channel)?.let { onPlayChannel(it, channel.name, channel.streamId in state.favoriteIds) }
                    infoChannel = null
                },
                onToggleFavourite = { onToggleFavorite(channel) },
                onDismiss = { infoChannel = null; restoreFocusToStreamId(channel.streamId) },
            )
        }

        com.livewire.tv.feature.favorites.ui.FavoriteToastHost(
            token = state.confirmation.token,
            message = state.confirmation.message,
            suppressed = infoChannel != null,
        )
    }
}

/** The ★ Favourites filter with no favourites yet (mockup guide-empty): a big outline star. */
@Composable
private fun FavoritesGuideEmpty() {
    Box(Modifier.fillMaxSize().padding(LiveWireDimens.SpaceL), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            com.livewire.tv.feature.favorites.ui.FavoriteStar(size = 56.dp, tint = LiveWireTheme.onSurfaceMuted)
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text(
                "No favourites yet",
                style = MaterialTheme.typography.headlineSmall,
                color = LiveWireTheme.onSurface,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Text(
                "Long-press OK on any channel to add it to Favourites",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireTheme.onSurfaceMuted,
            )
        }
    }
}

/** Shown in the grid when the filters leave no channels. */
@Composable
private fun NoMatches(query: String, categoryName: String) {
    Box(Modifier.fillMaxSize().padding(LiveWireDimens.SpaceL), Alignment.Center) {
        Text(
            if (query.isNotBlank()) "No channels in $categoryName match “${query.trim()}”."
            else "No channels in $categoryName.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireTheme.onSurfaceMuted,
        )
    }
}

/** Guide details band (design system §9.1, guide variant ~90dp): focused programme + a synopsis. */
@Composable
private fun GuideDetailsBand(
    focus: GuideFocus,
    now: Long,
    preview: GuidePreviewState,
    bandActions: Boolean,
    onWatchLive: () -> Unit,
    onStartOver: () -> Unit,
    onDismissActions: () -> Unit,
) {
    val prog = focus.programme
    val onNow = prog != null && prog.airsAt(now)
    // Catch-up: a focused archived-past programme offers "Watch from start"; the now programme
    // of an archive channel, once OK'd, offers Watch live (focused) / Start over.
    val showStartHint = focus.programmeArchived && prog != null && prog.stopMs <= now
    val showNowActions = bandActions && onNow && focus.channelHasArchive
    Box(Modifier.fillMaxWidth().height(DETAILS_BAND_HEIGHT)) {
        // The muted preview sits behind the text, fading in from the right.
        GuidePreviewLayer(preview)
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            when {
                onNow -> LiveBadge()
                focus.programmeArchived -> CatchupTag()
            }
            Text(
                listOfNotNull(focus.channel.number?.toString(), focus.channel.name).joinToString(" ").uppercase(),
                style = LiveWireType.Overline.copy(fontSize = 9.sp, letterSpacing = 0.12.em),
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Text(
                prog?.title ?: "No programme information",
                style = LiveWireType.TitleLarge,
                color = LiveWireTheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (prog != null) {
                // A Live Now event's end is a guess: name its start, which carries its own day.
                val range = if (prog.endIsEstimate) EventTimes.label(prog, now) else "${clockLabel(prog.startMs)} – ${clockLabel(prog.stopMs)}"
                val day = if (prog.endIsEstimate) null else dayLabel(prog.startMs, now)
                val left = if (onNow && !prog.endIsEstimate) " · ${minutesLeft(prog, now)}" else ""
                Text(
                    listOfNotNull(day, range).joinToString(" ") + left,
                    style = LiveWireType.Mono.copy(fontSize = 9.75.sp),
                    color = LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                )
            }
            }
            when {
                showNowActions -> {
                    val liveFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { liveFocus.requestFocus() } }
                    Row(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                // Back or Down returns to the grid cell.
                                Key.Back, Key.DirectionDown -> { onDismissActions(); true }
                                else -> false
                            }
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BandActionButton(R.drawable.ic_fav_play, "Watch live", onClick = onWatchLive, modifier = Modifier.focusRequester(liveFocus))
                        BandActionButton(R.drawable.ic_opt_reload, "Start over · ${clockLabel(prog!!.startMs)}", onClick = onStartOver)
                    }
                }
                showStartHint -> KeyHints("OK" to "Watch from start", "◀" to "Earlier")
                else -> KeyHints("OK" to "Watch", "◀" to "Earlier")
            }
        }
    }
}

/** The CATCH-UP tag for an archived past programme, in place of the LIVE badge. */
@Composable
private fun CatchupTag() {
    Row(
        Modifier.background(LiveWireColors.SurfaceRaised, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = LiveWireColors.OnSurface, modifier = Modifier.size(10.dp))
        Text("CATCH-UP", style = LiveWireType.Tag, color = LiveWireColors.OnSurface)
    }
}

/** A band action (Watch live / Start over): a pill, red at rest, the light fill when focused. */
@Composable
private fun BandActionButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.Live,
        restingBorder = false,
        shape = RoundedCornerShape(50),
        modifier = modifier.height(28.dp),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = LiveWireTheme.onSurface, modifier = Modifier.size(12.dp))
            Text(label, style = LiveWireType.Title.copy(fontSize = 10.5.sp), color = LiveWireTheme.onSurface, maxLines = 1)
        }
    }
}

/**
 * The header lanes and the now-line follow the grid's horizontal scroll. [scrollPx] is read
 * inside `offset {}`, in the layout phase, so scrolling moves them without recomposing, and
 * the pixel offset is subtracted in pixels (it is not a dp value).
 */

/**
 * NOW lane: a strip above the axis. The pill lives in a viewport box that starts at the
 * channel column and clips to it, so it can never sit over the channel column or the axis
 * header. When now scrolls off the left, the pill pins to the viewport's left edge.
 */
@Composable
private fun NowLane(nowMin: Int?, scrollPx: () -> Int, now: Long, windowStartMs: Long) {
    val density = LocalDensity.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(NOW_LANE),
    ) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp)), Alignment.CenterStart) {
            // Scrolled back into an earlier day: name the day at the lane's left edge. Read
            // from the scroll position, so it updates as the grid scrolls.
            val leftDay by remember(windowStartMs, now) {
                derivedStateOf {
                    val minutes = with(density) { scrollPx().toDp().value } / PX_PER_MINUTE
                    dayLabel(windowStartMs + TimeUnit.MINUTES.toMillis(minutes.toLong()), now, upper = true)
                }
            }
            leftDay?.let { day ->
                Text(
                    day,
                    style = LiveWireTheme.tokens.tag,
                    color = LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (nowMin != null) {
                Box(
                    Modifier
                        // Pin to the viewport's left edge once now has scrolled off.
                        .offset { IntOffset((minutesToDp(nowMin).roundToPx() - scrollPx()).coerceAtLeast(0), 0) }
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiveWireColors.Live)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("NOW ${clockLabel(now).removeSuffix(" AM").removeSuffix(" PM")}", style = LiveWireType.Tag.copy(fontSize = 8.25.sp), color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

/** Time-axis header: a channel corner plus 30-minute tick labels, scrolled with the rows. */
@Composable
private fun AxisRow(windowStartMs: Long, spanMinutes: Int, scrollPx: () -> Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(AXIS_HEIGHT)
            .gridLine(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(CHANNEL_COL).fillMaxHeight().padding(start = LiveWireDimens.SpaceM),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("CHANNEL", style = LiveWireType.Overline, color = LiveWireTheme.onSurfaceMuted)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            var m = 0
            while (m < spanMinutes) {
                val tick = m
                Text(
                    clockLabel(windowStartMs + TimeUnit.MINUTES.toMillis(m.toLong())).removeSuffix(" AM").removeSuffix(" PM"),
                    style = LiveWireType.Mono.copy(fontSize = 9.75.sp),
                    color = LiveWireTheme.onSurfaceMuted,
                    modifier = Modifier
                        .offset { IntOffset(minutesToDp(tick).roundToPx() - scrollPx(), 0) }
                        .padding(start = 6.dp),
                    maxLines = 1,
                )
                m += 30
            }
        }
    }
}

/**
 * The red now-line spanning the rows area. It sits in a viewport Row that reserves the
 * channel column then clips the remainder, so the line is confined to the programme area
 * and drawn at the current time under the shared scroll. Hidden once now scrolls off left.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.NowLine(nowMin: Int, scrollPx: () -> Int, topInset: Dp) {
    Row(Modifier.matchParentSize()) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            Box(
                Modifier
                    .offset {
                        val x = minutesToDp(nowMin).roundToPx() - scrollPx()
                        // Once now has scrolled out of view to the left, park the line off-screen.
                        IntOffset(if (x < 0) -LINE_PARK_PX else x, topInset.roundToPx())
                    }
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(LiveWireColors.Live),
            )
        }
    }
}

// LocalBringIntoViewSpec is still experimental; the lane needs it to stop wide cells
// scrolling the timeline (see laneScrollDistance).
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideRowView(
    row: GuideRow,
    windowStartMs: Long,
    windowSpanMs: Long,
    now: Long,
    hScroll: androidx.compose.foundation.ScrollState,
    laneWidth: Dp,
    spanMinutes: Int,
    anchorMs: Long,
    anchorRequester: FocusRequester,
    onFocus: (EpgProgramme?) -> Unit,
    onClick: (EpgProgramme?) -> Unit,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    laneStepping: () -> Boolean = { false },
) {
    Row(Modifier.height(ROW_HEIGHT).gridLine()) {
        // Fixed channel column: number, logo, name. Favourites carry a star, archive channels
        // the catch-up mark after the name.
        Row(
            Modifier.width(CHANNEL_COL).fillMaxHeight().background(LiveWireColors.Canvas).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                row.channel.number?.toString() ?: "",
                style = LiveWireType.Number,
                color = LiveWireTheme.onSurface,
                maxLines = 1,
                modifier = Modifier.width(30.dp),
            )
            com.livewire.tv.feature.home.ChannelLogo(row.channel, Modifier.size(width = 40.dp, height = 25.dp), textSize = 7)
            if (isFavorite) {
                com.livewire.tv.feature.favorites.ui.FavoriteStar(size = 10.dp, tint = LiveWireTheme.onSurface)
            }
            Text(
                row.channel.name,
                style = LiveWireType.Title.copy(fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
                color = LiveWireTheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.channel.hasArchive) {
                Icon(painterResource(R.drawable.ic_archive), contentDescription = "Catch-up", tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(10.dp))
            }
        }
        // Programme lane on the shared timeline / scroll. Focusing a cell already in view
        // does not move the timeline (see [laneScrollDistance]).
        val platformSpec = LocalBringIntoViewSpec.current
        val laneSpec = remember(platformSpec) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                    laneScrollDistance(offset, size, containerSize, stepping = laneStepping())
                        ?: platformSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides laneSpec) {
        Row(Modifier.horizontalScroll(hScroll).width(laneWidth)) {
            val cells = laneCells(row.programmes, windowStartMs, windowSpanMs)
            // The cell Up/Down (and a restore) lands on: the one under the anchor time.
            val anchorIndex = cellIndexForAnchor(cells, anchorMs)
            if (cells.isEmpty()) {
                // A row with no programmes exposes a single empty cell.
                EmptyCell(
                    scrollPx = { hScroll.value },
                    widthDp = minutesToDp(spanMinutes),
                    focusRequester = anchorRequester,
                    onFocus = { onFocus(null) },
                    onClick = { onClick(null) },
                    onLongClick = onLongClick,
                )
            } else {
                val startMinutes = cellStartMinutes(cells)
                cells.forEachIndexed { cellIndex, cell ->
                  // Keyed by start time: past listings arrive later and are inserted at the front
                  // of the row, and without a key the focused slot would start drawing an
                  // earlier programme, so focus would drift into the past on its own.
                  key(cell.programme.startMs) {
                    if (cell.gapMinutes > 0) Spacer(Modifier.width(minutesToDp(cell.gapMinutes)))
                    val style = CatchupGuide.cellStyle(cell.programme.stopMs, row.channel.hasArchive, now)
                    ProgrammeCell(
                        programme = cell.programme,
                        widthDp = minutesToDp(cell.minutes),
                        startDp = minutesToDp(startMinutes[cellIndex]),
                        scrollPx = { hScroll.value },
                        now = now,
                        pastStyle = style,
                        showBadge = CatchupGuide.showArchiveBadge(style, cell.minutes) && row.isArchived(cell.programme),
                        focusRequester = anchorRequester.takeIf { cellIndex == anchorIndex },
                        onFocus = { onFocus(cell.programme) },
                        onClick = { onClick(cell.programme) },
                        onLongClick = onLongClick,
                    )
                  }
                }
            }
        }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: EpgProgramme,
    widthDp: Dp,
    startDp: Dp,
    scrollPx: () -> Int,
    now: Long,
    pastStyle: GuidePastCellStyle,
    showBadge: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val onNow = programme.airsAt(now)
    // A past programme on a no-archive channel is dimmed; archived past and current are full.
    val dimmed = CatchupGuide.isDimmed(pastStyle)
    LiveWireSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        // On now = raised; later and archived past = surface; past with no recording = sunken.
        restingColor = when {
            dimmed -> LiveWireColors.SurfaceSunken
            onNow -> LiveWireColors.SurfaceRaised
            else -> LiveWireColors.Surface
        },
        restingBorder = false,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .width(widthDp)
            .height(ROW_HEIGHT)
            .padding(start = 2.dp, end = 1.dp, top = 2.dp, bottom = 3.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .androidx_onFocus(onFocus),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            // Keep the text at the lane's visible left edge when the cell starts off screen
            // (see stickyTextShift). Read in the layout phase, so scrolling only re-places it.
            Column(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().layout { measurable, constraints ->
                    val shift = stickyTextShift(
                        cellStartPx = startDp.roundToPx(),
                        scrollPx = scrollPx(),
                        textAreaPx = constraints.maxWidth,
                        minTextPx = STICKY_TEXT_MIN.roundToPx(),
                    )
                    val width = constraints.maxWidth - shift
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(shift, 0) }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showBadge) {
                        Icon(painterResource(R.drawable.ic_archive), contentDescription = "In catch-up", tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        programme.title,
                        style = LiveWireType.Title.copy(fontSize = 10.5.sp),
                        color = when {
                            LocalOnFocus.current -> LiveWireColors.OnFocus
                            dimmed -> LiveWireColors.OnSurfaceFaint
                            onNow -> LiveWireColors.OnSurface
                            else -> LiveWireColors.OnSurfaceSoft
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The title truncates before the badge: it takes the row's width minus
                        // the badge's reserved space (handled by weighting the title).
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    if (programme.endIsEstimate) EventTimes.label(programme, now)
                    else "${clockLabel(programme.startMs)} – ${clockLabel(programme.stopMs)}",
                    style = LiveWireType.Mono.copy(fontSize = 8.25.sp),
                    color = if (dimmed && !LocalOnFocus.current) LiveWireColors.OnFocusMuted else LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Full-width no-programme-information cell for a channel with no guide data (§9.4). Focusable. */
@Composable
private fun EmptyCell(scrollPx: () -> Int, widthDp: Dp, focusRequester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    LiveWireSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        restingColor = LiveWireColors.Surface,
        restingBorder = false,
        shape = RoundedCornerShape(4.dp),
        // No focus scale: the cell is the whole window wide (days), so even 2% moved its
        // on-screen part by hundreds of pixels. The focus border still marks it.
        focusedScale = 1f,
        modifier = Modifier.width(widthDp).height(ROW_HEIGHT).padding(2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .androidx_onFocus(onFocus),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = LiveWireDimens.SpaceM), contentAlignment = Alignment.CenterStart) {
            // The cell spans the whole window, so keep the label at the lane's visible left
            // edge instead of at the window start, days off screen.
            Text(
                "No programme information",
                style = LiveWireType.Sub,
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.offset { IntOffset(scrollPx(), 0) },
            )
        }
    }
}

/**
 * Whether the guide's last focus move was a Left/Right step, which may scroll a lane to show
 * the landing cell's start, or an Up/Down or guide-placed move, which keeps the lane still
 * (see [laneScrollDistance]). Read during bring-into-view rather than composition, so it is a
 * plain holder, not state.
 */
private class LaneMode {
    var stepping = false
}

/** "36 min left", or "ends soon" in the last minute. */
private fun minutesLeft(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

/** The 1dp grid line along the bottom of a header or row. */
private fun Modifier.gridLine(): Modifier = drawBehind {
    drawRect(LiveWireColors.Border, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
}

/** Far enough left to be clipped by the lane viewport. */
private const val LINE_PARK_PX = 10_000

private fun minutesToDp(minutes: Int): Dp = (minutes * PX_PER_MINUTE).dp

// Rebuilt when the device locale changes, so times follow a language switch mid-session.
private var timeFmt: Pair<Locale, SimpleDateFormat>? = null
private fun clockLabel(ms: Long): String {
    val locale = Locale.getDefault()
    val fmt = timeFmt?.takeIf { it.first == locale }?.second
        ?: SimpleDateFormat("h:mm a", locale).also { timeFmt = locale to it }
    return fmt.format(Date(ms))
}

// Rebuilt with the locale, like [clockLabel].
private var dayFmt: Pair<Locale, SimpleDateFormat>? = null

/**
 * The short weekday and date ("Thu 1 Oct") when [ms] falls on a different calendar day from
 * [now], else null. Used where a clock time alone would be ambiguous after scrolling back.
 */
internal fun dayLabel(ms: Long, now: Long, upper: Boolean = false): String? {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = now
    val today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    if (day == today) return null
    val locale = Locale.getDefault()
    val fmt = dayFmt?.takeIf { it.first == locale }?.second
        ?: SimpleDateFormat("EEE d MMM", locale).also { dayFmt = locale to it }
    val label = fmt.format(Date(ms))
    return if (upper) label.uppercase(locale) else label
}

/** onFocusChanged that fires our callback only when this cell gains focus. */
private fun Modifier.androidx_onFocus(onFocus: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) onFocus() }

/** Room kept at the band's right edge for the preview's "PREVIEW · MUTED" tag. */
private val PREVIEW_TAG_CLEARANCE = 150.dp

/** How far rewind / fast-forward jump the Guide's browsed time. */
private const val GUIDE_JUMP_MS = 3L * 60 * 60 * 1000
