package com.livewire.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.feature.settings.data.AppSettings
import com.livewire.tv.feature.settings.data.StreamFormat
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, restyled to the design system as mockup Option 1 (single grouped list). One
 * vertical focus column of section groups (PLAYBACK, GUIDE, HOME, PROVIDERS, ABOUT); each is a
 * mono overline over one or more [LiveWireSurface] rows carrying a title + one-line description
 * on the left and a remote-friendly control on the right.
 *
 * Sizing note: this TV target renders at density 2.0 (a 1920×1080 panel is 960×540 dp), so the
 * mockup's 1920-wide pixel measurements map to dp at roughly half their value. The tokens and
 * dp values below are chosen so all five sections plus the bottom hint line fit at 1080p with no
 * scrolling, matching option1.png.
 *
 * Only the settings the app already has (SettingsStore): stream format, guide window,
 * now-playing, providers, about. No new settings, no behaviour change to the store.
 *
 * Controls (§9.7, §7): stream format is a two-segment choice (OK cycles TS↔HLS, white check on
 * the selected segment); guide window is a left/right stepper (D-pad Left/Right when the row is
 * focused decrement/increment within 2–8, OK also steps up); now-playing is a switch with a
 * written On/Off state and a white — never amber — track/knob when on; providers opens the
 * manager; about is a dashed, non-focusable info row.
 *
 * Colour discipline (§3.3): amber lives only on the focus ring (via [LiveWireSurface]) and the
 * nav marker (owned by the nav shell). Every colour/spacing value comes from Theme.kt tokens;
 * no hardcoded hex here. Content sits inside the 48dp/27dp safe area beside the drawer (§2);
 * Left-from-first-column and Back → drawer are handled by the nav shell, untouched here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenProviders: () -> Unit,
    onOpenFavorites: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val providerSummary by viewModel.providerSummary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshProviderSummary() }

    // The self-update surface, scoped to the Activity so a check started here shares state with
    // Home (and its dialog can appear over either screen).
    val updateViewModel = com.livewire.tv.feature.update.rememberActivityUpdateViewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // Focus the first row (Stream format) when Settings opens, so the PLAYBACK header is visible
    // and the first D-pad press acts on a setting instead of falling into the nav drawer — the
    // same pattern Home uses for its first card.
    val firstRowFocus = remember { FocusRequester() }
    var firstRowHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var held = 0
        repeat(30) {
            if (firstRowHasFocus) held++ else {
                held = 0
                runCatching { firstRowFocus.requestFocus() }
            }
            if (held >= 3) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = LiveWireDimens.SafeHorizontal,
                        end = LiveWireDimens.SafeHorizontal,
                        top = LiveWireDimens.SafeVertical,
                        bottom = LiveWireDimens.SafeVertical,
                    ),
            ) {
                TopLine(providerSummary = providerSummary)
                Spacer(Modifier.height(LiveWireDimens.SpaceL))
                SettingsList(
                    s = s,
                    providerSummary = providerSummary,
                    viewModel = viewModel,
                    updateViewModel = updateViewModel,
                    updateState = updateState,
                    onOpenProviders = onOpenProviders,
                    onOpenFavorites = onOpenFavorites,
                    firstRowFocus = firstRowFocus,
                    onFirstRowFocusChanged = { firstRowHasFocus = it },
                    modifier = Modifier.weight(1f),
                )
                HintLine()
            }

            // The manual "Check for updates" row can surface the update dialog over Settings
            // (the user asked here), so Settings hosts the same shared overlay Home does.
            com.livewire.tv.feature.update.UpdateOverlay(viewModel = updateViewModel)
        }
    }
}

/** Page title + active-provider summary on the left, a live clock on the right (mockup top row). */
@Composable
private fun TopLine(providerSummary: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Text(
            providerSummary,
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.padding(bottom = 2.dp).weight(1f),
        )
        Text(
            rememberClockLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}

/** The "Thu · 8:24 PM"-style clock label, formatted from the current time. */
@Composable
private fun rememberClockLabel(): String = remember {
    SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(Date())
}

/**
 * The single grouped list, constrained to roughly the mockup's width (~62% of the content area)
 * and left-aligned to the same content start as the title. A [LazyColumn] with small vertical
 * content padding so the first/last rows' focus ring + wide scale are never clipped; no left
 * inset, so rows align with the title (mockup point 3). One focus column: ▼/▲ walk the rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsList(
    s: AppSettings,
    providerSummary: String,
    viewModel: SettingsViewModel,
    updateViewModel: com.livewire.tv.feature.update.UpdateViewModel,
    updateState: com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState,
    onOpenProviders: () -> Unit,
    onOpenFavorites: () -> Unit,
    firstRowFocus: FocusRequester,
    onFirstRowFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        // widthIn WITHOUT fillMaxWidth: the column measures up to ListMaxWidth and no wider, so
        // the list occupies ~62% of the content area and leaves the right side empty like the
        // mockup. Rows fillMaxWidth() inside this capped column.
        modifier = modifier.widthIn(max = ListMaxWidth),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
        // Only vertical padding: keeps the focus ring/scale off the top and bottom edges while
        // leaving rows flush with the title on the left. A hair of horizontal padding keeps the
        // 1.02 wide-scale ring off the hard edges.
        contentPadding = PaddingValues(
            start = RingInset, end = RingInset, top = RingInset, bottom = RingInset,
        ),
    ) {
        item("playback") {
            SettingsGroup("Playback") {
                StreamFormatRow(
                    current = s.streamFormat,
                    onCycle = { viewModel.setStreamFormat(SettingsFormat.toggleStreamFormat(s.streamFormat)) },
                    focusRequester = firstRowFocus,
                    onFocusChanged = onFirstRowFocusChanged,
                )
            }
        }
        item("guide") {
            SettingsGroup("Guide") {
                GuideWindowRow(
                    hours = s.guideWindowHours,
                    onDecrement = { viewModel.setGuideWindowHours(SettingsFormat.decrementGuideWindow(s.guideWindowHours)) },
                    onIncrement = { viewModel.setGuideWindowHours(SettingsFormat.incrementGuideWindow(s.guideWindowHours)) },
                )
                Spacer(Modifier.height(LiveWireDimens.SpaceXs))
                GuidePreviewRow(on = s.guidePreview) { viewModel.setGuidePreview(!s.guidePreview) }
            }
        }
        item("home") {
            SettingsGroup("Home") {
                NowPlayingRow(on = s.showNowPlayingOnCards) { viewModel.setShowNowPlaying(!s.showNowPlayingOnCards) }
            }
        }
        item("favorites") {
            SettingsGroup("Favourites") {
                FavoritesRow(onOpen = onOpenFavorites)
            }
        }
        item("providers") {
            SettingsGroup("Providers") {
                ProvidersRow(
                    summary = when (providerSummary) {
                        "" -> "Add, edit, or switch"
                        SettingsFormat.NO_PROVIDER -> "Add a provider"
                        else -> "$providerSummary — add, edit, or switch"
                    },
                    onOpen = onOpenProviders,
                )
            }
        }
        item("about") {
            SettingsGroup("About") {
                AboutGroup(
                    s = s,
                    updateState = updateState,
                    onCheckNow = updateViewModel::checkNow,
                    onToggleAutoCheck = { updateViewModel.setAutoCheck(!s.autoCheckUpdates) },
                )
            }
        }
    }
}

/** A group: a mono overline header with a short bar, over its row(s). Tight gap below the header. */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = HeaderGap),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(LiveWireColors.OnSurfaceMuted),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            Text(
                title.uppercase(),
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }
        content()
    }
}

/**
 * The shared row chrome: a full-width [LiveWireSurface] (wide focus scale so the ring never
 * clips) with the title + description on the left and the control cluster on the right. Row
 * height is kept near the mockup's ~46dp (92px ÷ 2) via a min-height + compact padding. A
 * [modifier] is applied to the surface so a row (the guide stepper) can intercept keys.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.Surface,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = LiveWireDimens.SpaceM)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            control()
        }
    }
}

/** Stream format: a two-segment choice. OK on the row cycles to the other value. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamFormatRow(
    current: StreamFormat,
    onCycle: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Stream format",
        description = "How channels are requested from the provider",
        onClick = onCycle,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    ) {
        SegmentedChoice(current)
    }
}

/**
 * A two-segment control mirroring the mockup: MPEG-TS | HLS. The selected segment carries a
 * [LiveWireColors.SurfaceFocused] fill, weight-700 label and a white check glyph — never amber.
 * It is presentation only; OK on the enclosing row does the cycling.
 */
@Composable
private fun SegmentedChoice(current: StreamFormat) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(text = "MPEG-TS", selected = current == StreamFormat.TS)
        Box(Modifier.width(1.dp).height(ControlHeight).background(LiveWireColors.Border))
        Segment(text = "HLS", selected = current == StreamFormat.HLS)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean) {
    Row(
        modifier = Modifier
            .heightIn(min = ControlHeight)
            .background(if (selected) LiveWireColors.SurfaceFocused else LiveWireColors.SurfaceRaised)
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = LiveWireColors.OnSurface,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
        )
    }
}

/**
 * Guide window: a left/right value stepper. With the row focused, D-pad Left decrements and
 * Right increments within 2–8; OK also steps up (stopping at 8), matching the mockup's
 * "◀ ▶ change value / OK select". A mono caption under the value names the range and the value
 * before this edit session.
 *
 * The row consumes only Left/Right key-downs (via onPreviewKeyEvent) so ▼/▲ still move between
 * rows and Left from the first *column* still reaches the drawer — this row is not the first
 * column, so consuming Left/Right here does not interfere with the nav shell.
 */
@Composable
private fun GuideWindowRow(hours: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    // The value at the start of this edit session, for the "was N" caption. Captured on first
    // composition; the caption appears once the live value differs from it.
    var baseline by remember { mutableIntStateOf(hours) }
    val changed = hours != baseline

    SettingRow(
        title = "Guide window",
        description = "Hours of programmes shown across the timeline",
        onClick = onIncrement,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> {
                    if (SettingsFormat.canDecrementGuideWindow(hours)) { onDecrement(); true } else false
                }
                Key.DirectionRight -> {
                    if (SettingsFormat.canIncrementGuideWindow(hours)) { onIncrement(); true } else false
                }
                else -> false
            }
        },
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepArrow(glyph = "‹", enabled = SettingsFormat.canDecrementGuideWindow(hours), onClick = onDecrement)
                Text(
                    SettingsFormat.guideWindowLabel(hours),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = LiveWireColors.OnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 68.dp),
                    maxLines = 1,
                )
                StepArrow(glyph = "›", enabled = SettingsFormat.canIncrementGuideWindow(hours), onClick = onIncrement)
            }
            if (changed) {
                Spacer(Modifier.height(2.dp))
                Text(
                    SettingsFormat.guideWindowRangeCaption(baseline),
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A compact bordered stepper arrow (~24dp square, mockup 48px ÷ 2) drawn with a chevron glyph.
 * Neutral when idle; when [enabled] its glyph and border brighten to signal it responds to D-pad
 * Left/Right. Never amber.
 */
@Composable
private fun StepArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Box(
        modifier = Modifier
            .padding(horizontal = LiveWireDimens.SpaceXs)
            .size(ControlHeight)
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(
                LiveWireDimens.RestBorder,
                if (enabled) LiveWireColors.BorderStrong else LiveWireColors.Border,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
        )
    }
}

/** Guide channel preview on/off, the same switch as the now-playing row. */
@Composable
private fun GuidePreviewRow(on: Boolean, onToggle: () -> Unit) {
    SettingRow(
        title = "Channel preview",
        description = "Play the focused channel, muted, after 2 seconds",
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SettingsFormat.onOffLabel(on),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurface,
                modifier = Modifier.widthIn(min = 26.dp),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            NeutralSwitch(on = on)
        }
    }
}

/** Now-playing: a switch with a written On/Off state. White track + knob when on, never amber. */
@Composable
private fun NowPlayingRow(on: Boolean, onToggle: () -> Unit) {
    SettingRow(
        title = "Show now-playing on cards",
        description = "Display the current programme under each channel",
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SettingsFormat.onOffLabel(on),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurface,
                modifier = Modifier.widthIn(min = 26.dp),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            NeutralSwitch(on = on)
        }
    }
}

/**
 * A presentation-only switch matching the mockup (64×34px → ~32×17dp): ON = white track + a
 * canvas-coloured knob to the right (neutral/white, NOT amber); OFF = raised track + muted knob
 * to the left. Toggling is done by OK on the enclosing row.
 */
@Composable
private fun NeutralSwitch(on: Boolean) {
    val trackShape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(18.dp)
            .clip(trackShape)
            .background(if (on) LiveWireColors.OnSurface else LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, trackShape),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) LiveWireColors.Canvas else LiveWireColors.OnSurfaceMuted),
        )
    }
}

/** Favourites: a "Manage ›" affordance. OK opens the Settings → Favourites screen. */
@Composable
private fun FavoritesRow(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    SettingRow(
        title = "Manage favourites",
        description = "Reorder or remove your favourite channels",
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ControlHeight)
                .clip(shape)
                .background(LiveWireColors.SurfaceRaised)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            Text("›", style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurfaceMuted)
        }
    }
}

/** Providers: a "Manage ›" affordance. OK opens the provider manager screen. */
@Composable
private fun ProvidersRow(summary: String, onOpen: () -> Unit) {    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    SettingRow(
        title = "Manage providers",
        description = summary,
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ControlHeight)
                .clip(shape)
                .background(LiveWireColors.SurfaceRaised)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }
    }
}

/**
 * About: the version+build row, a focusable "Check for updates" row, the auto-check switch, and
 * a "Last checked" row (brief step 5). Debug builds where updates are compiled out
 * ([BuildConfig.UPDATES_ENABLED] false) show a Check row that reads "Updates are off in debug
 * builds" and does nothing.
 *
 * The Check row's subtitle reflects the live [updateState]: "Checking…", "Version X is
 * available", the up-to-date line, or an error's message. OK on it runs a manual check; the
 * dialog (hosted by the screen's [UpdateOverlay]) appears if a newer version is found.
 */
@Composable
private fun AboutGroup(
    s: AppSettings,
    updateState: com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState,
    onCheckNow: () -> Unit,
    onToggleAutoCheck: () -> Unit,
) {
    val fmt = com.livewire.tv.feature.update.UpdateFormat
    val updatesEnabled = com.livewire.tv.BuildConfig.UPDATES_ENABLED
    val currentVersion = fmt.cleanVersion(com.livewire.tv.BuildConfig.VERSION_NAME)

    // Version + build (info row, non-focusable).
    AboutInfoRow(
        title = fmt.versionLine(com.livewire.tv.BuildConfig.VERSION_NAME, com.livewire.tv.BuildConfig.VERSION_CODE.toLong()),
        description = "Android TV IPTV player",
    )
    Spacer(Modifier.height(LiveWireDimens.SpaceXs))

    // Check for updates.
    val checkSubtitle = when {
        !updatesEnabled -> fmt.DEBUG_DISABLED_SUBTITLE
        updateState is com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState.Checking -> fmt.CHECKING_SUBTITLE
        updateState is com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState.Available ->
            fmt.availableSubtitle(updateState.version)
        updateState is com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState.Failed ->
            updateState.error.message
        updateState is com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState.UpToDate ->
            fmt.upToDateSubtitle(updateState.current)
        else -> fmt.upToDateSubtitle(currentVersion)
    }
    SettingRow(
        title = "Check for updates",
        description = checkSubtitle,
        onClick = { if (updatesEnabled) onCheckNow() },
    ) {
        val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
        Row(
            modifier = Modifier
                .heightIn(min = ControlHeight)
                .clip(shape)
                .background(LiveWireColors.SurfaceRaised)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Check now",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (updatesEnabled) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }

    if (updatesEnabled) {
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        // Auto-check switch (white when on, never amber).
        SettingRow(
            title = "Check for updates automatically",
            description = "Once a day when LiveWire starts — never while you're watching",
            onClick = onToggleAutoCheck,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SettingsFormat.onOffLabel(s.autoCheckUpdates),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurface,
                    modifier = Modifier.widthIn(min = 26.dp),
                )
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
                NeutralSwitch(on = s.autoCheckUpdates)
            }
        }

        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        // Last checked (info row, relative time).
        val lastChecked = remember(s.lastUpdateCheck) {
            val now = System.currentTimeMillis()
            val abs = if (s.lastUpdateCheck > 0L) {
                SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(s.lastUpdateCheck))
            } else {
                null
            }
            fmt.lastCheckedLabel(s.lastUpdateCheck, now, abs)
        }
        AboutInfoRow(title = "Last checked", description = "Automatic daily check", trailing = lastChecked)
    }
}

/**
 * A non-focusable About info row: title + description on the left, an optional muted [trailing]
 * value on the right. Bordered like the other rows but takes no focus (§9.9).
 */
@Composable
private fun AboutInfoRow(title: String, description: String, trailing: String? = null) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(shape)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/** The bottom remote-hint line (mockup): ▲▼ Move · ◀▶ Change value · OK Select. */
@Composable
private fun HintLine() {
    Row(
        modifier = Modifier.padding(top = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
    ) {
        Hint("▲ ▼", "Move")
        Hint("◀ ▶", "Change value")
        Hint("OK", "Select")
    }
}

@Composable
private fun Hint(keys: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(keys, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

// ── Layout constants tuned to the mockup at this target's density 2.0 (1920px ≈ 960dp). ──
/** ~62% of the content area beside the rail (mockup .list ≈ 1000px → ~500dp). */
private val ListMaxWidth = 520.dp
/** Gap between section groups (mockup 34px → ~17dp; trimmed so all 5 groups + hint fit). */
private val GroupGap = 13.dp
/** Gap under a group's overline header (mockup 16px → ~8dp). */
private val HeaderGap = 6.dp
/** Row min height (mockup .row 92px → ~46dp). */
private val RowMinHeight = 44.dp
/** Height of the right-side controls (segments, arrows, provider button). */
private val ControlHeight = 30.dp
/** Vertical breathing room so the first/last focus ring + wide scale are never clipped. */
private val RingInset = LiveWireDimens.SpaceS
