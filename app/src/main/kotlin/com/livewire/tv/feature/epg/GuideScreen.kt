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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No guide data for this provider.")
            }
            else -> GuideContent(state, onPlayChannel) { channel -> viewModel.playbackTarget(channel) }
        }
    }
}

@Composable
private fun GuideContent(
    state: GuideUiState,
    onPlayChannel: (PlaybackTarget, String) -> Unit,
    targetFor: (LiveChannel) -> PlaybackTarget?,
) {
    val now = System.currentTimeMillis()
    val hScroll = rememberScrollState()
    val spanMinutes = TimeUnit.MILLISECONDS.toMinutes(state.windowSpanMs).toInt()
    val laneWidth = minutesToDp(spanMinutes)

    // The details band follows the focused cell. Default to the first row's on-now programme
    // so the band is populated before the user moves focus.
    val firstRow = state.rows.first()
    var focus by remember(state.rows) {
        mutableStateOf(GuideFocus(firstRow.channel, onNow(firstRow.programmes, now)))
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
        GuideDetailsBand(focus, now)
        Spacer(Modifier.height(LiveWireDimens.SpaceS))

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
                NowLane(nowMin, hScroll.value, now)
                AxisRow(state.windowStartMs, spanMinutes, hScroll.value)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows) { row ->
                        GuideRowView(
                            row = row,
                            windowStartMs = state.windowStartMs,
                            windowSpanMs = state.windowSpanMs,
                            now = now,
                            hScroll = hScroll,
                            laneWidth = laneWidth,
                            spanMinutes = spanMinutes,
                            onFocus = { prog -> focus = GuideFocus(row.channel, prog) },
                            onClick = { targetFor(row.channel)?.let { onPlayChannel(it, row.channel.name) } },
                        )
                    }
                }
            }
            // Red now-line, drawn above the cells, below the header lanes. It lives in a
            // viewport that starts at the channel column and clips to it, so it never crosses
            // into the channel column or the axis header; it hides once now scrolls off the left.
            if (nowMin != null) {
                NowLine(nowMin, hScroll.value, topInset = NOW_LANE + AXIS_HEIGHT)
            }
        }
    }
}

/** Guide details band (design system §9.1, guide variant ~90dp): focused programme + a synopsis. */
@Composable
private fun GuideDetailsBand(focus: GuideFocus, now: Long) {
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
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
    ) {
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

/**
 * NOW lane: a strip above the axis. The pill lives in a viewport box that starts at the
 * channel column and clips to it, so it can never sit over the channel column or the axis
 * header. When now scrolls off the left, the pill pins to the viewport's left edge.
 */
@Composable
private fun NowLane(nowMin: Int?, scrollPx: Int, now: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(NOW_LANE)
            .background(LiveWireColors.Surface),
    ) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp)), Alignment.CenterStart) {
            if (nowMin != null) {
                val raw = minutesToDp(nowMin) - scrollPx.dp
                val x = raw.coerceAtLeast(0.dp) // pin to the viewport's left edge when scrolled off
                Box(
                    Modifier
                        .offset(x = x)
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
private fun AxisRow(windowStartMs: Long, spanMinutes: Int, scrollPx: Int) {
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
                val x = minutesToDp(m) - scrollPx.dp
                Text(
                    clockLabel(windowStartMs + TimeUnit.MINUTES.toMillis(m.toLong())),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurface,
                    modifier = Modifier.offset(x = x).padding(start = 6.dp),
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
private fun androidx.compose.foundation.layout.BoxScope.NowLine(nowMin: Int, scrollPx: Int, topInset: Dp) {
    val raw = minutesToDp(nowMin) - scrollPx.dp
    if (raw < 0.dp) return // now has scrolled out of view to the left; hide the line
    Row(Modifier.matchParentSize()) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            Box(
                Modifier
                    .offset(x = raw, y = topInset)
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
            if (cells.isEmpty()) {
                EmptyCell(widthDp = minutesToDp(spanMinutes), onFocus = { onFocus(null) }, onClick = onClick)
            } else {
                cells.forEach { cell ->
                    if (cell.gapMinutes > 0) Spacer(Modifier.width(minutesToDp(cell.gapMinutes)))
                    ProgrammeCell(
                        programme = cell.programme,
                        widthDp = minutesToDp(cell.minutes),
                        now = now,
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
private fun EmptyCell(widthDp: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCell),
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier.width(widthDp).height(ROW_HEIGHT).padding(2.dp).androidx_onFocus(onFocus),
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

private fun minutesToDp(minutes: Int): Dp = (minutes * PX_PER_MINUTE).dp

/** The programme on now in [programmes], or null. */
private fun onNow(programmes: List<EpgProgramme>, now: Long): EpgProgramme? =
    programmes.firstOrNull { it.airsAt(now) }

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun clockLabel(ms: Long): String = timeFmt.format(Date(ms))

/** onFocusChanged that fires our callback only when this cell gains focus. */
private fun Modifier.androidx_onFocus(onFocus: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) onFocus() }
