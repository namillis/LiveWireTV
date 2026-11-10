package com.livewire.tv.feature.settings

import android.text.Layout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.core.player.CaptionStyling
import com.livewire.tv.feature.settings.data.CaptionBackground
import com.livewire.tv.feature.settings.data.CaptionColor
import com.livewire.tv.feature.settings.data.CaptionEdge
import com.livewire.tv.feature.settings.data.CaptionFont
import com.livewire.tv.feature.settings.data.CaptionPlacement
import com.livewire.tv.feature.settings.data.CaptionSize
import com.livewire.tv.feature.settings.data.SubtitleStyle
import com.livewire.tv.feature.settings.data.canStep
import com.livewire.tv.feature.settings.data.step
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.Overline

/**
 * Settings › Subtitles (mockup design/mockups/bgrid/subtitles, option B). One row per caption
 * property on the left, a preview on the right that redraws as each value changes. The preview
 * is a bundled photo with sample captions drawn by the same Media3 [SubtitleView] and
 * [CaptionStyling] the player uses, so it matches what a channel shows.
 *
 * Every choice row takes ◀ ▶ to step through its values (stopping at either end) and OK to
 * cycle forward. Changes save at once; Back returns to Settings (handled by the nav host).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SubtitlesScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val style = settings.subtitleStyle
    val update = viewModel::updateSubtitleStyle

    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { firstRowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Subtitles", style = LiveWireType.Heading, color = LiveWireTheme.onSurface)
                Spacer(Modifier.width(LiveWireDimens.SpaceM))
                Text(
                    "Settings  ›  Subtitles",
                    style = LiveWireType.Mono,
                    color = LiveWireTheme.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Row(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = ListMaxWidth),
                    verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXs),
                    contentPadding = PaddingValues(LiveWireDimens.SpaceS),
                ) {
                    item("size") {
                        StepRow(
                            title = "Text size",
                            description = "How big captions are drawn",
                            value = style.size,
                            label = style.size.label,
                            onChange = { next -> update { it.copy(size = next(it.size)) } },
                            modifier = Modifier.focusRequester(firstRowFocus),
                        )
                    }
                    item("color") {
                        ChoiceRow(
                            title = "Text color",
                            options = CaptionColor.entries,
                            value = style.color,
                            label = { it.label },
                            swatch = { Color(it.argb) },
                            onChange = { next -> update { it.copy(color = next(it.color)) } },
                        )
                    }
                    item("background") {
                        ChoiceRow(
                            title = "Background",
                            options = CaptionBackground.entries,
                            value = style.background,
                            label = { it.label },
                            onChange = { next -> update { it.copy(background = next(it.background)) } },
                        )
                    }
                    item("edge") {
                        ChoiceRow(
                            title = "Edge",
                            description = "Helps text read without a box",
                            options = CaptionEdge.entries,
                            value = style.edge,
                            label = { it.label },
                            onChange = { next -> update { it.copy(edge = next(it.edge)) } },
                        )
                    }
                    item("font") {
                        ChoiceRow(
                            title = "Font",
                            options = CaptionFont.entries,
                            value = style.font,
                            label = { it.label },
                            onChange = { next -> update { it.copy(font = next(it.font)) } },
                        )
                    }
                    item("placement") {
                        ChoiceRow(
                            title = "Placement",
                            options = CaptionPlacement.entries,
                            value = style.placement,
                            label = { it.label },
                            onChange = { next -> update { it.copy(placement = next(it.placement)) } },
                        )
                    }
                    item("reset") {
                        OpenRow(
                            title = "Reset to default",
                            description = if (style.isDefault) "Already the default" else SubtitleStyle().summary,
                            action = "Reset",
                            onOpen = { update { SubtitleStyle() } },
                        )
                    }
                }
                Spacer(Modifier.width(LiveWireDimens.SpaceXl))
                CaptionPreview(style, Modifier.weight(1f).padding(top = LiveWireDimens.SpaceS))
            }
            Row(
                modifier = Modifier.padding(top = LiveWireDimens.SpaceS),
                horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
            ) {
                Hint("▲ ▼", "Move")
                Hint("◀ ▶", "Change value")
                Hint("BACK", "Settings")
            }
        }
    }
}

/** ◀ ▶ (and OK, forward) consumed by a row; Up/Down still move between rows. */
private fun Modifier.leftRight(onStep: (forward: Boolean) -> Unit) = onPreviewKeyEvent { event ->
    // Consume both ends too, so ◀ ▶ at a limit doesn't move focus off the row.
    when (event.key) {
        Key.DirectionLeft, Key.DirectionRight -> {
            if (event.type == KeyEventType.KeyDown) onStep(event.key == Key.DirectionRight)
            true
        }
        else -> false
    }
}

/**
 * Text size: a stepper like Settings' Guide window. [onChange] receives a function from the
 * SAVED value to the next one and runs it inside the store's transaction, so two quick presses
 * step twice even before the screen recomposes.
 */
@Composable
private fun StepRow(
    title: String,
    description: String,
    value: CaptionSize,
    label: String,
    onChange: ((CaptionSize) -> CaptionSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        title = title,
        description = description,
        // OK steps up and wraps from Extra large back to Small.
        onClick = { onChange { cur -> if (cur.canStep(true)) cur.step(true) else CaptionSize.entries.first() } },
        modifier = modifier.leftRight { forward -> onChange { cur -> cur.step(forward) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepArrow(glyph = "‹", enabled = value.canStep(false), onClick = { onChange { it.step(false) } })
            Text(
                label,
                style = LiveWireType.Mono,
                fontWeight = FontWeight.Bold,
                color = LiveWireTheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 84.dp),
                maxLines = 1,
            )
            StepArrow(glyph = "›", enabled = value.canStep(true), onClick = { onChange { it.step(true) } })
        }
    }
}

/**
 * A segmented choice; ◀ ▶ move the selection, OK cycles forward and wraps. Like [StepRow],
 * [onChange] gets a function from the saved value to the next, so quick presses don't collapse.
 */
@Composable
private fun <E> ChoiceRow(
    title: String,
    options: List<E>,
    value: E,
    label: (E) -> String,
    onChange: ((E) -> E) -> Unit,
    description: String = "",
    swatch: ((E) -> Color)? = null,
) {
    fun index(of: E) = options.indexOf(of).coerceAtLeast(0)
    SettingRow(
        title = title,
        description = description,
        onClick = { onChange { cur -> options[(index(cur) + 1) % options.size] } },
        modifier = Modifier.leftRight { forward ->
            onChange { cur -> options[(index(cur) + if (forward) 1 else -1).coerceIn(0, options.lastIndex)] }
        },
    ) {
        val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
        Row(
            modifier = Modifier.clip(shape).border(LiveWireDimens.RestBorder, LiveWireTheme.controlBorder, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                if (index > 0) Box(Modifier.width(1.dp).height(ControlHeight).background(LiveWireTheme.controlBorder))
                val color = swatch?.invoke(option)
                Segment(
                    text = label(option),
                    selected = option == value,
                    leading = color?.let { c ->
                        {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(c)
                                    .border(1.dp, LiveWireTheme.controlBorder, RoundedCornerShape(2.dp)),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * The live preview: a bundled photo (CC0, see third_party/images/SOURCES.md) with sample captions
 * drawn by Media3's [SubtitleView] through [CaptionStyling], exactly as the player draws them.
 * The sample sits lower-left, the way a broadcaster places pop-on captions, so switching to
 * Always bottom visibly moves it.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun CaptionPreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    Column(modifier) {
        Overline("PREVIEW")
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape),
        ) {
            Image(
                painter = painterResource(R.drawable.subtitle_preview_frame),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AndroidView(
                factory = { ctx -> SubtitleView(ctx) },
                update = { view ->
                    CaptionStyling.apply(view, style)
                    view.setCues(CaptionStyling.placeCues(SAMPLE_CUES, style.placement))
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            when (style.placement) {
                CaptionPlacement.AS_BROADCAST -> "Sample · placed where the broadcaster puts it"
                CaptionPlacement.BOTTOM -> "Sample · every caption centered at the bottom"
            },
            style = LiveWireType.Mono,
            color = LiveWireTheme.onSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two caption rows near the bottom left, as CEA-608 pop-on captions arrive (line numbers count up from the bottom). */
private val SAMPLE_CUES: List<Cue> by lazy { sampleCues() }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun sampleCues(): List<Cue> {
    val cues = ArrayList<Cue>()
    for ((text, line) in listOf(">> AND THE TEAMS ARE BACK OUT" to -3f, "FOR THE SECOND HALF." to -2f)) {
        cues += Cue.Builder()
            .setText(text)
            .setLine(line, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.1f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }
    return cues
}
