package com.livewire.tv.feature.player

import com.livewire.tv.ui.theme.brandEdge
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.livewire.tv.R
import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.Overline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.core.player.VideoFormat
import com.livewire.tv.feature.home.wordmark
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/** Everything the archive / time-shift overlays render, built by the screen from engine state. */
data class ArchiveOverlayModel(
    val channelName: String,
    val channelNumber: String,
    /** The past programme's title (catch-up) or null when time-shifting live. */
    val programmeTitle: String?,
    val programmeCategory: String?,
    /** Absolute UTC start of the archive window — the "Aired <day> <time>" label. */
    val windowStartMs: Long,
    /** Current absolute UTC position within the window. */
    val positionMs: Long,
    /** Window length in millis (catch-up) — the scrub bar's total. */
    val durationMs: Long,
    val video: VideoFormat?,
    val audioChannelCount: Int,
    val hasSubtitles: Boolean,
)

/**
 * Catch-up player overlay (mockups option2-player.png / option2-player-bar.png). Grey ⟲ ARCHIVE
 * pill (never the red LIVE pill), "Aired <day> <time> · watching mm:ss of mm:ss", a grey scrub
 * bar whose knob is white/grey until [barFocused] gives it the amber ring, and the Option 2
 * hint chips. Purely presentational.
 */
@Composable
fun ArchivePlayerOverlay(
    model: ArchiveOverlayModel,
    state: PlaybackState,
    barFocused: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
    /** A seek is pending: [model]'s position is where it will land, and OK jumps there now. */
    scrubbing: Boolean = false,
    /** Playing a paused-live stream behind live (not catch-up): TIME-SHIFTED pill and labels. */
    timeShift: Boolean = false,
    /** When live was first paused; the "paused at" label, fixed across reopens. */
    pausedAtMs: Long = 0L,
) {
    Box(modifier.fillMaxSize()) {
        BottomScrim()
        if (timeShift) {
            ArchiveTopBug(model.channelNumber, pill = "TIME-SHIFTED", archive = false)
        } else {
            ArchiveTopBug(model.channelNumber, pill = "ARCHIVE", archive = true)
        }
        Clock(now, Modifier.align(Alignment.TopEnd))

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical),
        ) {
            // Channel identity row + the right-hand quality chips.
            Row(verticalAlignment = Alignment.Bottom) {
                ChannelIdentity(
                    channelName = model.channelName,
                    channelNumber = model.channelNumber,
                    stateLabel = if (timeShift) "Time-shifted" else "Catch-up",
                    modifier = Modifier.weight(1f),
                )
                QualityChips(model.video, model.audioChannelCount, model.hasSubtitles)
            }

            Spacer(Modifier.height(LiveWireDimens.SpaceM))

            // CATCH-UP <title> · <category>
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
                Overline(if (timeShift) "Time-shifted" else "Catch-up")
                Text(
                    model.programmeTitle ?: model.channelName,
                    style = LiveWireType.Display,
                    color = LiveWireTheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
                model.programmeCategory?.takeIf { it.isNotBlank() }?.let {
                    Text("· $it", style = LiveWireType.Body, color = LiveWireTheme.onSurfaceMuted)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                if (timeShift) {
                    "${PlayerFormats.behindLive((now - model.positionMs).coerceAtLeast(0L)).replaceFirstChar { it.uppercase() }} · paused at ${PlayerFormats.clockMeridiem(pausedAtMs.takeIf { it > 0L } ?: model.windowStartMs)}"
                } else {
                    "Aired ${PlayerFormats.dayAndTime(model.windowStartMs)} · ${PlayerFormats.watchingOf(model.positionMs - model.windowStartMs, model.durationMs)}"
                },
                style = LiveWireType.Body,
                color = LiveWireTheme.onSurfaceMuted,
            )

            // Room for the position tooltip above the knob, so it never covers the line above.
            Spacer(Modifier.height(SCRUB_TOOLTIP_GAP))
            ScrubBar(
                fraction = fractionOf(model.positionMs - model.windowStartMs, model.durationMs),
                leftLabel = PlayerFormats.duration(model.positionMs - model.windowStartMs),
                rightLabel = PlayerFormats.remaining(model.positionMs - model.windowStartMs, model.durationMs),
                knobFocused = barFocused,
                liveEdge = false,
            )

            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            if (barFocused) {
                HintRow(
                    HintSpec("◀ ▶", "Seek · hold to go faster"),
                    HintSpec("OK", if (scrubbing) "Jump now" else "Play / pause"),
                    HintSpec("▲", "Go live"),
                    HintSpec("▼ / BACK", "Release bar"),
                )
            } else {
                HintRow(
                    HintSpec("▼", "Focus scrub bar"),
                    HintSpec("◀ ▶ (on bar)", "Seek"),
                    HintSpec("OK", "Play / pause"),
                    HintSpec("▲", "Go live"),
                )
            }
        }
    }
}

/**
 * Paused-live / time-shift overlay (mockup option2-paused.png). Grey TIME-SHIFTED pill, a centre
 * pause glyph, "Paused · X behind live", a neutral Go-live chip, and a bottom scrub whose right
 * end is the red live edge. No amber item anywhere.
 */
@Composable
fun PausedLiveOverlay(
    model: ArchiveOverlayModel,
    behindMs: Long,
    now: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        BottomScrim()
        ArchiveTopBug(model.channelNumber, pill = "TIME-SHIFTED", archive = false)
        Clock(now, Modifier.align(Alignment.TopEnd))

        // Centre pause stack.
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("❚❚", style = LiveWireType.Heading, color = LiveWireTheme.onSurface)
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text("Paused", style = LiveWireType.Heading, color = LiveWireTheme.onSurface)
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Text(
                PlayerFormats.behindLive(behindMs),
                style = LiveWireType.Body,
                color = LiveWireTheme.onSurfaceMuted,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            NeutralChip("▲ Go live")
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical),
        ) {
            ChannelIdentity(
                channelName = model.channelName,
                channelNumber = model.channelNumber,
                stateLabel = "Paused — time-shifted",
            )
            // Room for the position tooltip above the knob, so it never covers the line above.
            Spacer(Modifier.height(SCRUB_TOOLTIP_GAP))
            ScrubBar(
                fraction = 1f, // behind-live fill runs to the live edge
                leftLabel = "-${PlayerFormats.duration(behindMs)}",
                rightLabel = "LIVE",
                knobFocused = false,
                liveEdge = true,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            HintRow(
                HintSpec("OK", "Resume"),
                HintSpec("▲", "Go live (jump to now)"),
            )
        }
    }
}

/**
 * One-time "This channel can't rewind" card (mockup option2-norewind.png). Neutral (not red —
 * §3.3), shown over live video on a no-archive channel when the user tries to time-shift.
 */
@Composable
fun NoRewindCard(channelName: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .widthIn(max = 680.dp)
                .clip(RoundedCornerShape(LiveWireDimens.RadiusDialog))
                .background(LiveWireColors.Surface)
                .border(1.dp, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusDialog))
                .brandEdge()
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusDialog))
                .padding(LiveWireDimens.SpaceL),
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(LiveWireDimens.RadiusCell)).background(LiveWireColors.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(
                    "This channel can’t rewind",
                    style = LiveWireType.Title.copy(fontWeight = FontWeight.Bold),
                    color = LiveWireTheme.onSurface,
                )
                Spacer(Modifier.height(LiveWireDimens.SpaceS))
                Text(
                    "$channelName has no catch-up archive, so you can’t go back or start over. " +
                        "Pausing still works — press OK to pause, and OK again to rejoin live.",
                    style = LiveWireType.Body,
                    color = LiveWireTheme.onSurfaceMuted,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Spacer(Modifier.height(LiveWireDimens.SpaceM))
                HintChip("OK", "Got it")
            }
        }
    }
}

// --- Shared pieces -----------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomScrim() {
    // Same top + bottom gradients as the live RestingOverlay, so the pill, clock and the
    // bottom text stay legible over bright video in every player mode.
    Box(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(150.dp)
            .background(
                Brush.verticalGradient(
                    0f to LiveWireColors.Canvas.copy(alpha = 0.75f),
                    1f to Color.Transparent,
                ),
            ),
    )
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to LiveWireColors.Canvas.copy(alpha = 0.78f),
                    0.5f to LiveWireColors.Canvas.copy(alpha = 0.94f),
                    1f to LiveWireColors.Canvas.copy(alpha = 0.98f),
                ),
            ),
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ArchiveTopBug(channelNumber: String, pill: String, archive: Boolean) {
    Row(
        Modifier.align(Alignment.TopStart).padding(start = LiveWireDimens.SafeHorizontal, top = 22.dp),
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
        // A raised state tag, never the red LIVE badge: this is not live.
        Row(
            Modifier.background(LiveWireColors.SurfaceRaised, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (archive) Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = LiveWireColors.OnSurface, modifier = Modifier.size(10.dp))
            Text(pill, style = LiveWireType.Tag.copy(fontSize = 8.25.sp), color = LiveWireColors.OnSurface)
        }
    }
}

@Composable
private fun Clock(now: Long, modifier: Modifier) {
    Text(
        PlayerFormats.clockMeridiem(now),
        style = LiveWireType.Mono,
        color = LiveWireTheme.onSurface,
        modifier = modifier.padding(end = LiveWireDimens.SafeHorizontal, top = LiveWireDimens.SafeVertical),
    )
}

@Composable
private fun ChannelIdentity(
    channelName: String,
    channelNumber: String,
    stateLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL)) {
        Text(
            wordmark(channelName),
            style = LiveWireType.Heading,
            color = LiveWireTheme.onSurface,
            maxLines = 1,
        )
        Column {
            Text(
                channelName,
                style = LiveWireType.Title.copy(fontWeight = FontWeight.Bold),
                color = LiveWireTheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                if (channelNumber.isNotBlank()) add("CH $channelNumber")
                add(stateLabel)
            }.joinToString(" · ")
            Text(sub, style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
        }
    }
}

@Composable
private fun QualityChips(video: VideoFormat?, audioChannelCount: Int, hasSubtitles: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
        video?.let { PlayerFormats.resolutionLabel(it.width, it.height) }?.takeIf { it.isNotBlank() }?.let { OutlineChip(it) }
        PlayerFormats.channelLayout(audioChannelCount).uppercase().takeIf { it.isNotBlank() }?.let { OutlineChip(it) }
        if (hasSubtitles) OutlineChip("CC")
    }
}

@Composable
private fun OutlineChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(5.dp))
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 4.dp),
    ) {
        Text(label, style = LiveWireType.Tag, color = LiveWireTheme.onSurface)
    }
}

/**
 * The seek bar (mockup B-grid-31): watched up to the playhead in the light line, and where the
 * playhead sits a light handle. When [knobFocused] the handle grows and a time tag in the focus
 * fill sits above it. [liveEdge] marks the live edge at the right with the red dot.
 */
@Composable
private fun ScrubBar(
    fraction: Float,
    leftLabel: String,
    rightLabel: String,
    knobFocused: Boolean,
    liveEdge: Boolean,
) {
    val track = 6.dp
    val knob = if (knobFocused) 15.dp else 11.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(leftLabel, style = LiveWireType.Mono, color = LiveWireColors.OnSurface)
        BoxWithConstraints(Modifier.weight(1f).height(knob + 6.dp)) {
            val f = fraction.coerceIn(0f, 1f)
            val knobX: Dp = (maxWidth - knob) * f
            Box(Modifier.fillMaxWidth().height(track).align(Alignment.CenterStart).clip(RoundedCornerShape(50)).background(LiveWireColors.ProgressTrack))
            Box(Modifier.fillMaxWidth(f).height(track).align(Alignment.CenterStart).clip(RoundedCornerShape(50)).background(LiveWireColors.ProgressFill))
            if (liveEdge) {
                Box(
                    Modifier.align(Alignment.CenterEnd).size(11.dp).clip(CircleShape).background(LiveWireColors.Canvas).padding(2.dp)
                        .clip(CircleShape).background(LiveWireColors.Live),
                )
            }
            if (knobFocused) {
                Text(
                    leftLabel,
                    style = LiveWireType.Mono.copy(fontWeight = FontWeight.SemiBold),
                    color = LiveWireColors.OnFocus,
                    modifier = Modifier
                        .offset(x = knobX - 20.dp, y = -(knob + 12.dp))
                        .clip(RoundedCornerShape(5.dp))
                        .background(LiveWireColors.Focus)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = knobX)
                    .size(knob + 6.dp)
                    .clip(CircleShape)
                    .background(LiveWireColors.Canvas.copy(alpha = 0.85f))
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(LiveWireColors.Focus),
            )
        }
        Text(rightLabel, style = LiveWireType.Mono, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** Tooltip height plus its offset above the knob (see [ScrubBar]). */
private val SCRUB_TOOLTIP_GAP = 30.dp

private data class HintSpec(val key: String, val label: String)

@Composable
private fun HintRow(vararg hints: HintSpec) {
    KeyHints(*hints.map { it.key to it.label }.toTypedArray())
}

@Composable
private fun NeutralChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
    ) {
        Text(label, style = LiveWireType.Mono, color = LiveWireTheme.onSurface)
    }
}

@Composable
private fun HintChip(key: String, label: String) {
    KeyHints(key to label)
}

private fun fractionOf(elapsedMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
