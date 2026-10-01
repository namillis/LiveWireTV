package com.livewire.tv.feature.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import kotlinx.coroutines.launch

/**
 * The self-update dialog (mockup Option 1): a focus-trapped centred card over a 55% scrim,
 * rendering whichever [UpdateUiState] is live. It is shown on Home only (the caller decides
 * that) and never over the player or onboarding.
 *
 * One dialog handles the whole flow: the update-available prompt, download progress, the
 * verify step, the unknown-apps permission error, and any other failure. States that carry no
 * user decision ([UpdateUiState.Idle], [UpdateUiState.Checking], [UpdateUiState.UpToDate],
 * [UpdateUiState.Installing]) render nothing here — Installing hands off to Android's own
 * confirm screen, and the terminal "Updated to X" note lives on Home, not in this dialog.
 *
 * Colour discipline (§3.3): amber appears only on the focus ring (via [LiveWireSurface]).
 * The "Update available" tag is a NEUTRAL chip (surface fill + on-surface text), a deliberate
 * deviation from the mockup's amber pill so amber stays focus-only. The progress bar is the
 * neutral [LiveWireProgress] (grey), never amber. The error icon is on-surface, not red.
 *
 * All callbacks map 1:1 to [UpdateViewModel] actions; this file holds no update logic.
 */
@Composable
fun UpdateDialogHost(
    state: UpdateUiState,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AvailableDialog(state, onUpdateNow, onLater, onSkip)
        is UpdateUiState.Downloading -> ProgressDialog(
            pct = state.pct, bytes = state.bytes, total = state.total, verifying = false, onCancel = onCancel,
        )
        is UpdateUiState.Verifying -> ProgressDialog(
            pct = 100, bytes = 0, total = 0, verifying = true, onCancel = onCancel,
        )
        is UpdateUiState.NeedsUnknownSourcesPermission -> PermissionDialog(state, onOpenSettings, onCancel)
        is UpdateUiState.Failed -> FailedDialog(state, onTryAgain, onDismiss)
        // No dialog for Idle / Checking / UpToDate / Installing.
        else -> Unit
    }
}

// ── Scaffold ─────────────────────────────────────────────────────────────────────

/**
 * Request focus onto [target] when a dialog first shows, and keep re-requesting for a short
 * window until it sticks. The host screen (Home) may be running its own focus loop as the
 * dialog appears, so a single request can be overwritten; retrying for ~300ms lets the dialog
 * reliably win the focus trap. Home also stops its own loop while a dialog is up (isDialogState),
 * so this settles quickly.
 */
@Composable
private fun RequestInitialFocus(target: FocusRequester) {
    LaunchedEffect(Unit) {
        repeat(12) {
            runCatching { target.requestFocus() }
            kotlinx.coroutines.delay(25)
        }
    }
}

/**
 * The shared scrim + centred card, mirroring the Providers screen's dialog language (§9.5).
 * Focus is trapped because the card's children are the only focus targets composed while the
 * dialog is up. Back runs [onBack].
 */
@Composable
private fun DialogScaffold(onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    Box(modifier = Modifier.fillMaxSize().background(LiveWireColors.Scrim), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(LiveWireDimens.RadiusDialog)
        Column(
            modifier = Modifier
                .widthIn(max = DialogMaxWidth)
                .fillMaxWidth()
                .clip(shape)
                .background(LiveWireColors.Surface)
                .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                .padding(DialogPadding)
                // Trap focus inside the card: it is a focus group, and the content behind the
                // scrim is dimmed but the ring never moves onto it because every focusable here
                // clamps its own edges (see the action rows' focusProperties).
                .focusGroup(),
            content = content,
        )
    }
}

// ── Update available ───────────────────────────────────────────────────────────────

/**
 * The update-available prompt: neutral "UPDATE AVAILABLE" tag + "You're on X", title
 * "LiveWire X is available", a meta line, the What's-new notes (D-pad-scrollable), and the
 * Update now / Later / Skip this version action row. Update now takes initial focus; Back =
 * Later.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AvailableDialog(
    state: UpdateUiState.Available,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val updateFocus = remember { FocusRequester() }
    RequestInitialFocus(updateFocus)

    DialogScaffold(onBack = onLater) {
        // Tag + "You're on X"
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeutralTag("Update available")
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
            Text(
                "You're on ${state.currentVersion}",
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text(
            "LiveWire ${state.version} is available",
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Text(
            state.metaLine,
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )

        val notes = state.notes
        val notesFocus = remember { FocusRequester() }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            GroupOverline("What's new")
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            NotesBox(lines = notes, focusRequester = notesFocus, downOnExit = updateFocus)
        }

        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        Row(
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
            modifier = Modifier
                .fillMaxWidth()
                // Up from the action row reaches the notes box when there is one (so the user can
                // scroll the changelog), otherwise it stays put — either way focus never escapes
                // the dialog upward onto the dimmed screen behind.
                .focusProperties { if (notes.isNotEmpty()) up = notesFocus },
        ) {
            ActionButton(
                label = "Update now",
                onClick = onUpdateNow,
                modifier = Modifier.weight(1f).focusRequester(updateFocus),
                icon = ActionIcon.DOWNLOAD,
            )
            ActionButton(label = "Later", onClick = onLater, icon = ActionIcon.CLOCK)
            ActionButton(label = "Skip this version", onClick = onSkip, icon = ActionIcon.CLOSE)
        }
    }
}

/**
 * The What's-new box: a raised panel of bullet lines, capped to ~6 visible lines and
 * D-pad-scrollable when longer. The box itself is the focusable scroll region — Up/Down while it
 * holds focus scroll the [LazyColumn]; its focus ring shows it takes focus. It sits BEFORE the
 * action row so ▼ from it reaches the buttons ([downOnExit]) and its own Up is clamped to itself
 * so focus never leaves the dialog upward (the title/tag above are not focusable).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NotesBox(lines: List<UpdateFormat.NoteLine>, focusRequester: FocusRequester, downOnExit: FocusRequester) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Show whole lines only: the viewport is an exact multiple of the fixed line height, so a
    // line is never half-clipped. When more lines exist than fit (or the list can still scroll),
    // a "▼ scroll for more" cue is faded in over the bottom, matching the mockup.
    val fits = lines.size <= NotesVisibleLines
    val boxHeight = NotesLineHeight * minOf(lines.size, NotesVisibleLines)
    val showCue by remember {
        androidx.compose.runtime.derivedStateOf { !fits && listState.canScrollForward }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(boxHeight + NotesVPadding * 2)
            .clip(shape)
            .background(LiveWireColors.Canvas)
            .border(
                if (focused) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder,
                if (focused) LiveWireColors.Accent else LiveWireColors.Border,
                shape,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            // Up stays inside the box (clamped to itself); Down leaves to the action row.
            .focusProperties { up = focusRequester; down = downOnExit }
            .focusable()
            // While focused, Up/Down scroll the notes instead of moving focus; at the top edge
            // Up is swallowed (nothing above in the dialog is focusable), and Down past the
            // bottom falls through to the action row via the focusProperties above.
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        if (atTop) {
                            true // swallow: stay in the box, don't escape upward
                        } else {
                            // Step by whole lines so the viewport never lands mid-line.
                            scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) }
                            true
                        }
                    }
                    Key.DirectionDown -> {
                        val atBottom = !listState.canScrollForward
                        if (atBottom) {
                            false // let focus move down to the action row
                        } else {
                            scope.launch { listState.scrollToItem(listState.firstVisibleItemIndex + 1) }
                            true
                        }
                    }
                    else -> false
                }
            },
    ) {
        // Padding sits OUTSIDE the list so its viewport is exactly N whole lines. With the
        // padding inside (contentPadding), the line above the first visible one is drawn into
        // the top padding and shows half-clipped after scrolling.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NotesVPadding)
                .height(boxHeight),
            contentPadding = PaddingValues(horizontal = LiveWireDimens.SpaceL),
        ) {
            items(lines) { line -> NoteLineRow(line) }
        }
        if (showCue) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(NotesCueHeight)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.6f to LiveWireColors.Canvas,
                        ),
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    "▼ scroll for more",
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireColors.OnSurfaceMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/** One What's-new line at a fixed height (so lines never clip). Only real list items get a dot. */
@Composable
private fun NoteLineRow(line: UpdateFormat.NoteLine) {
    Row(
        modifier = Modifier.height(NotesLineHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (line.bullet) {
            Box(
                Modifier
                    .padding(end = LiveWireDimens.SpaceS)
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LiveWireColors.OnSurfaceMuted),
            )
        }
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Download / verify progress ──────────────────────────────────────────────────────

/**
 * Download progress and the verify step share one layout (mockup option1-progress): a big
 * percent readout, a meta line, the neutral progress bar, a two-step Download → Check list
 * with the active step marked, and a focused Cancel. During [verifying] the percent reads
 * "Checking…" and the second step becomes active. OK/Back cancel.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgressDialog(pct: Int, bytes: Long, total: Long, verifying: Boolean, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    RequestInitialFocus(cancelFocus)

    DialogScaffold(onBack = onCancel) {
        NeutralTag(if (verifying) "Checking the download" else "Downloading update")
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text(
            if (verifying) "Checking the download…" else "Getting the update",
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceL))

        if (!verifying) {
            Text(
                "$pct%",
                style = MaterialTheme.typography.displaySmall,
                color = LiveWireColors.OnSurface,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            Text(
                UpdateFormat.progressBytes(bytes, total),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            LiveWireProgress(fraction = pct / 100f, modifier = Modifier.fillMaxWidth(), height = 8.dp)
        } else {
            // Indeterminate-feeling full bar during verify (checksum/signature check is quick).
            LiveWireProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 8.dp)
        }

        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        StepRow(label = "Downloading the update", sub = "from the LiveWire releases page on GitHub", icon = if (verifying) ActionIcon.CHECK else ActionIcon.DOWNLOAD, active = !verifying, done = verifying)
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        StepRow(label = "Checking the download…", sub = "makes sure it's the genuine, complete file before installing", icon = ActionIcon.SHIELD, active = verifying, done = false)

        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { up = cancelFocus; down = cancelFocus },
        ) {
            // Compact, left-aligned like the mockup — a full-width Cancel reads as the main action.
            ActionButton(label = "Cancel", onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus), icon = ActionIcon.CLOSE)
        }
    }
}

/** One step in the download→verify checklist. [active] brightens it; [done] marks it complete. */
@Composable
private fun StepRow(label: String, sub: String, icon: ActionIcon, active: Boolean, done: Boolean) {
    val titleColor = if (active || done) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Round badge with a line glyph (mockup: ↓ for download, shield for the check, ✓ once done).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(StepBadgeSize)
                .clip(RoundedCornerShape(50))
                .background(if (active || done) LiveWireColors.SurfaceRaised else LiveWireColors.Surface)
                .border(1.dp, LiveWireColors.OnSurfaceMuted.copy(alpha = 0.35f), RoundedCornerShape(50)),
        ) {
            ButtonGlyph(icon, color = titleColor, glyphSize = 12.dp)
        }
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Unknown-sources permission error ─────────────────────────────────────────────────

/**
 * The "install from unknown apps is turned off" screen (mockup option1-error): a warning glyph
 * (on-surface, NOT red), a one-line explanation, and Open settings (focused) + Cancel. When the
 * device hides the settings page ([UpdateUiState.NeedsUnknownSourcesPermission.settingsIntent]
 * is null) the manual steps are shown instead of a dead Open-settings button.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionDialog(
    state: UpdateUiState.NeedsUnknownSourcesPermission,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)
    val hasSettings = state.settingsIntent != null

    DialogScaffold(onBack = onCancel) {
        WarnGlyph()
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text(
            "Install from unknown apps is turned off",
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            if (hasSettings) {
                "To update itself, LiveWire needs your permission to install apps. Open settings, " +
                    "turn on \"Allow from this source\" for LiveWire, then come back — the update is " +
                    "downloaded and ready."
            } else {
                state.manualSteps
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM), modifier = Modifier.fillMaxWidth()) {
            if (hasSettings) {
                ActionButton(label = "Open settings", onClick = onOpenSettings, modifier = Modifier.weight(1f).focusRequester(focus), icon = ActionIcon.SETTINGS)
                ActionButton(label = "Cancel", onClick = onCancel, icon = ActionIcon.CLOSE)
            } else {
                // No settings page: the only action is to dismiss, so it takes focus.
                ActionButton(label = "Close", onClick = onCancel, modifier = Modifier.weight(1f).focusRequester(focus), icon = ActionIcon.CLOSE)
            }
        }
    }
}

// ── Generic failure ──────────────────────────────────────────────────────────────

/**
 * Any other [UpdateUiState.Failed]: the error's plain message + Try again (focused) / Close
 * (mockup error language, warning glyph on-surface).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FailedDialog(state: UpdateUiState.Failed, onTryAgain: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)

    DialogScaffold(onBack = onDismiss) {
        WarnGlyph()
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text(
            "Update couldn't finish",
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            state.error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM), modifier = Modifier.fillMaxWidth()) {
            ActionButton(label = "Try again", onClick = onTryAgain, modifier = Modifier.weight(1f).focusRequester(focus), icon = ActionIcon.RETRY)
            ActionButton(label = "Close", onClick = onDismiss, icon = ActionIcon.CLOSE)
        }
    }
}

// ── Shared bits ───────────────────────────────────────────────────────────────────

/**
 * A neutral status tag (mono, uppercase): surface-raised fill + on-surface text. A deliberate
 * deviation from the mockup's amber pill so amber stays reserved for focus (§3.3).
 */
@Composable
private fun NeutralTag(text: String) {
    Text(
        text.uppercase(),
        style = LiveWireTheme.tokens.tag,
        color = LiveWireColors.OnSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(50))
            .padding(horizontal = LiveWireDimens.SpaceM, vertical = 6.dp),
    )
}

/** A mono overline with a short bar, matching the Settings group headers. */
@Composable
private fun GroupOverline(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(LiveWireColors.OnSurfaceMuted),
        )
        Spacer(Modifier.width(LiveWireDimens.SpaceS))
        Text(text.uppercase(), style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** The warning glyph for error states — on-surface, never red (§3.3: red is live-only). */
@Composable
private fun WarnGlyph() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(LiveWireDimens.RadiusCard))
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusCard)),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
    }
}

/** The icon a dialog action button draws to the left of its label (mockup line icons). */
private enum class ActionIcon { DOWNLOAD, CLOCK, CLOSE, SETTINGS, RETRY, SHIELD, CHECK }

/** Round badge beside each download/verify step (mockup ~28px circle at 2x density). */
private val StepBadgeSize = 28.dp

/** A dialog action button (§9.5). Focus ring/scale/glow come from [LiveWireSurface] (amber). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ActionIcon? = null) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ButtonHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ButtonGlyph(icon)
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * A small line-drawn glyph for a button, mirroring the mockup's SVG icons without adding an icon
 * dependency: download = down arrow into a tray, clock = a face with hands, close = an ✕, and the
 * error-screen settings/retry marks. Drawn on-surface (never amber — amber is focus only).
 */
@Composable
private fun ButtonGlyph(icon: ActionIcon, color: androidx.compose.ui.graphics.Color = LiveWireColors.OnSurface, glyphSize: androidx.compose.ui.unit.Dp = 18.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = size.minDimension * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        when (icon) {
            ActionIcon.DOWNLOAD -> {
                // shaft
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.12f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                // arrowhead
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.44f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.44f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                // tray
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.85f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.85f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ActionIcon.CLOCK -> {
                drawCircle(color, radius = w * 0.40f, center = androidx.compose.ui.geometry.Offset(w / 2, h / 2), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, h / 2), androidx.compose.ui.geometry.Offset(w / 2, h * 0.28f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, h / 2), androidx.compose.ui.geometry.Offset(w * 0.66f, h / 2), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ActionIcon.CLOSE -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.25f), androidx.compose.ui.geometry.Offset(w * 0.75f, h * 0.75f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.75f, h * 0.25f), androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.75f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ActionIcon.SETTINGS -> {
                drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w / 2, h / 2), style = stroke)
                drawCircle(color, radius = w * 0.40f, center = androidx.compose.ui.geometry.Offset(w / 2, h / 2), style = stroke)
            }
            ActionIcon.SHIELD -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.85f, h * 0.22f)
                    lineTo(w * 0.85f, h * 0.48f)
                    quadraticTo(w * 0.85f, h * 0.80f, w * 0.5f, h * 0.94f)
                    quadraticTo(w * 0.15f, h * 0.80f, w * 0.15f, h * 0.48f)
                    lineTo(w * 0.15f, h * 0.22f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            ActionIcon.CHECK -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.52f), androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.76f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.76f), androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.26f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ActionIcon.RETRY -> {
                drawArc(
                    color = color,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.14f),
                    size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.72f),
                    style = stroke,
                )
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.10f), androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.34f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.34f), androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.34f), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

// ── Layout constants (tuned to the mockup at this target's density 2.0, 1920px ≈ 960dp). ──
/** Mockup modal 1120px → ~560dp. */
private val DialogMaxWidth = 560.dp
private val DialogPadding = 26.dp
/** One notes line's fixed row height, so a line is shown whole or not at all. */
private val NotesLineHeight = 24.dp
/** Visible notes lines before the box scrolls (mockup shows ~6). */
private const val NotesVisibleLines = 6
/** Vertical padding inside the notes box. */
private val NotesVPadding = 12.dp
/** The faded "▼ scroll for more" cue band at the bottom of a scrollable notes box. */
private val NotesCueHeight = 28.dp
private val ButtonHeight = 40.dp
