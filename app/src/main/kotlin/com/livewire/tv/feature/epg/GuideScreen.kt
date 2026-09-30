package com.livewire.tv.feature.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
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
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.home.brandTint
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private const val PX_PER_MINUTE = 3          // 3dp per minute = 90dp per 30 min (design system §9.4)
private val ROW_HEIGHT = 48.dp               // §9.4 row height; 7 full rows fit at 1080p
private val CHANNEL_COL = 120.dp
private val SLOT = (30 * PX_PER_MINUTE).dp   // one 30-minute tick
private val NOW_LANE = 22.dp
private val AXIS_HEIGHT = 26.dp
private val DETAILS_BAND_HEIGHT = 84.dp      // kept tight so the grid clears 7 rows

/** The focused guide cell: which channel + programme drives the details band. */
private data class GuideFocus(val channel: LiveChannel, val programme: EpgProgramme?)

/**
 * Full-screen EPG guide (design system §9.4). A details band for the focused programme leads
 * the screen; below it a time-axis header with a red NOW-line, then one horizontally-scrolling
 * lane of programme cells per channel. Rows start near now (GuideViewModel). Amber appears only
 * on the focused cell (via LiveWireSurface); red only on the now-line and NOW pill.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
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
                onQueryChange = viewModel::setQuery,
                onPlayChannel = onPlayChannel,
                targetFor = viewModel::playbackTarget,
                resolvePreview = viewModel::previewSource,
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
    onQueryChange: (String) -> Unit,
    onPlayChannel: (PlaybackTarget, String) -> Unit,
    targetFor: (LiveChannel) -> PlaybackTarget?,
    resolvePreview: suspend (String) -> PlaybackSource?,
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

    // True while a programme cell holds focus; the category column and drawer do not count.
    var gridFocused by remember { mutableStateOf(false) }
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

    /** Focus [rowIndex]'s cell at the anchor, scrolling it into the list first if needed. */
    fun focusRow(rowIndex: Int): Boolean {
        if (rowIndex !in latestRows.indices) return false
        scope.launch {
            val row = latestRows.getOrNull(rowIndex) ?: return@launch
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
            if (!visible) {
                listState.scrollToItem(rowIndex)
                withFrameNanos { }
            }
            programmaticMove = true
            if (runCatching { rowRequesters[row.channel.streamId]?.requestFocus() }.isFailure) {
                programmaticMove = false
            }
        }
        return true
    }

    // The details band follows the focused cell; before anything is focused it shows the
    // restored (or first) row's programme at the anchor.
    val restoreIndex = rows.indexOfFirst { it.channel.streamId == focusedStreamId }.coerceAtLeast(0)
    var focus by remember(rows) {
        val r = rows.getOrNull(restoreIndex)
        mutableStateOf(r?.let { GuideFocus(it.channel, it.programmes.firstOrNull { p -> p.airsAt(anchorMs) }) })
    }

    // On open (and on return), land the ring on the restored row -- the first row on a fresh
    // open -- at the anchor time. Wait for programmes so it lands on a real cell, and never
    // move focus the user has already moved.
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

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = LiveWireDimens.SafeHorizontal,
                end = LiveWireDimens.SafeHorizontal,
                top = LiveWireDimens.SafeVertical,
                bottom = LiveWireDimens.SafeVertical,
            ),
    ) {
        focus?.let { GuideDetailsBand(it, now, preview) }
        Spacer(Modifier.height(LiveWireDimens.SpaceS))

        Row(Modifier.fillMaxSize()) {
            GuideCategoryColumn(
                categories = state.categories,
                totalChannels = state.totalChannels,
                selectedId = state.categoryId,
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
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(LiveWireDimens.RadiusCard))
                    .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusCard)),
            ) {
                Column(Modifier.fillMaxSize()) {
                    NowLane(nowMin, { hScroll.value }, now)
                    AxisRow(state.windowStartMs, spanMinutes) { hScroll.value }
                    if (rows.isEmpty()) {
                        NoMatches(state.query, state.categoryName)
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
                                        focus = GuideFocus(row.channel, prog)
                                        focusedStreamId = row.channel.streamId
                                        if (programmaticMove) {
                                            programmaticMove = false
                                        } else {
                                            // A Left/Right move (or entering from the column):
                                            // the landing cell sets the time being browsed.
                                            anchorForCell(prog, now, state.windowStartMs)?.let { anchorMs = it }
                                            if (!focusPlaced) userMovedFocus = true
                                        }
                                        savePosition()
                                    },
                                    onClick = {
                                        // Close the preview's stream first: many providers
                                        // allow one connection, and the player needs it.
                                        preview.player.stop()
                                        savePosition()
                                        targetFor(row.channel)?.let { onPlayChannel(it, row.channel.name) }
                                    },
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
}

/** Shown in the grid when the filters leave no channels. */
@Composable
private fun NoMatches(query: String, categoryName: String) {
    Box(Modifier.fillMaxSize().padding(LiveWireDimens.SpaceL), Alignment.Center) {
        Text(
            if (query.isNotBlank()) "No channels in $categoryName match “${query.trim()}”."
            else "No channels in $categoryName.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}

/** Guide details band (design system §9.1, guide variant ~90dp): focused programme + a synopsis. */
@Composable
private fun GuideDetailsBand(focus: GuideFocus, now: Long, preview: GuidePreviewState) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    val brand = brandTint(focus.channel.name)
    val prog = focus.programme
    Box(
        Modifier
            .fillMaxWidth()
            .height(DETAILS_BAND_HEIGHT)
            .clip(shape)
            .background(LiveWireColors.Surface)
            .background(brand.copy(alpha = if (LiveWireTheme.tokens.tier == com.livewire.tv.ui.theme.PerformanceTier.LOW) 0.16f else 0.20f))
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape),
    ) {
        // The muted preview sits behind the text, fading in from the right.
        GuidePreviewLayer(preview)
        Box(Modifier.matchParentSize().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS)) {
            // Clock lives in the band (no page title, per §8).
            Text(
                clockLabel(now),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (prog != null && prog.airsAt(now)) {
                            LiveDot()
                            Spacer(Modifier.width(LiveWireDimens.SpaceS))
                        }
                        Text(
                            focus.channel.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = LiveWireColors.OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The band is kept short so the grid fits 7 rows: label, one-line title and
                    // one-line synopsis must fit its 68dp of inner height.
                    Spacer(Modifier.height(2.dp))
                    Text(
                        prog?.title ?: "No programme information",
                        style = MaterialTheme.typography.titleLarge,
                        color = LiveWireColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (prog != null) {
                        Spacer(Modifier.height(2.dp))
                        val range = "${clockLabel(prog.startMs)} – ${clockLabel(prog.stopMs)}"
                        val synopsis = prog.description?.takeIf { it.isNotBlank() }
                        Text(
                            synopsis ?: (range + (prog.category?.let { " · $it" } ?: "")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LiveWireColors.OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
        }
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
private fun NowLane(nowMin: Int?, scrollPx: () -> Int, now: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(NOW_LANE)
            .background(LiveWireColors.Surface),
    ) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp)), Alignment.CenterStart) {
            if (nowMin != null) {
                Box(
                    Modifier
                        // Pin to the viewport's left edge once now has scrolled off.
                        .offset { IntOffset((minutesToDp(nowMin).roundToPx() - scrollPx()).coerceAtLeast(0), 0) }
                        .clip(RoundedCornerShape(50))
                        .background(LiveWireColors.Live)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("NOW · ${clockLabel(now)}", style = LiveWireTheme.tokens.tag, color = Color.White, maxLines = 1)
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
            .background(LiveWireColors.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(CHANNEL_COL).fillMaxHeight().padding(start = LiveWireDimens.SpaceM),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("CHANNEL", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            var m = 0
            while (m < spanMinutes) {
                val tick = m
                Text(
                    clockLabel(windowStartMs + TimeUnit.MINUTES.toMillis(m.toLong())),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurface,
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
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(LiveWireColors.Live),
            )
        }
    }
}

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
    onClick: () -> Unit,
) {
    Row(Modifier.height(ROW_HEIGHT)) {
        // Fixed channel column.
        Box(
            Modifier.width(CHANNEL_COL).fillMaxHeight().background(LiveWireColors.Surface).padding(horizontal = LiveWireDimens.SpaceM),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                row.channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Programme lane on the shared timeline / scroll.
        Row(Modifier.horizontalScroll(hScroll).width(laneWidth)) {
            val cells = laneCells(row.programmes, windowStartMs, windowSpanMs)
            // The cell Up/Down (and a restore) lands on: the one under the anchor time.
            val anchorIndex = cellIndexForAnchor(cells, anchorMs)
            if (cells.isEmpty()) {
                // A row with no programmes exposes a single empty cell.
                EmptyCell(
                    widthDp = minutesToDp(spanMinutes),
                    focusRequester = anchorRequester,
                    onFocus = { onFocus(null) },
                    onClick = onClick,
                )
            } else {
                cells.forEachIndexed { cellIndex, cell ->
                    if (cell.gapMinutes > 0) Spacer(Modifier.width(minutesToDp(cell.gapMinutes)))
                    ProgrammeCell(
                        programme = cell.programme,
                        widthDp = minutesToDp(cell.minutes),
                        now = now,
                        focusRequester = anchorRequester.takeIf { cellIndex == anchorIndex },
                        onFocus = { onFocus(cell.programme) },
                        onClick = onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: EpgProgramme,
    widthDp: Dp,
    now: Long,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val onNow = programme.airsAt(now)
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCell),
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier
            .width(widthDp)
            .height(ROW_HEIGHT)
            .padding(2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .androidx_onFocus(onFocus),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = LiveWireDimens.SpaceM, vertical = 6.dp)) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${clockLabel(programme.startMs)} – ${clockLabel(programme.stopMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            // On-now cells carry a thin grey progress strip along the bottom (never amber/red).
            if (onNow) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(programme.progressAt(now))
                        .height(2.dp)
                        .background(LiveWireColors.ProgressFill),
                )
            }
        }
    }
}

/** Full-width no-programme-information cell for a channel with no guide data (§9.4). Focusable. */
@Composable
private fun EmptyCell(widthDp: Dp, focusRequester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCell),
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier.width(widthDp).height(ROW_HEIGHT).padding(2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .androidx_onFocus(onFocus),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = LiveWireDimens.SpaceM), contentAlignment = Alignment.CenterStart) {
            Text(
                "No programme information",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LiveDot() {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(LiveWireColors.Live))
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

/** onFocusChanged that fires our callback only when this cell gains focus. */
private fun Modifier.androidx_onFocus(onFocus: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) onFocus() }
