package com.livewire.tv.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.ui.provider.ProviderFieldLabelStyle
import com.livewire.tv.ui.provider.ProviderFilledField
import com.livewire.tv.ui.provider.ProviderFormLayout
import com.livewire.tv.ui.provider.PasswordRevealToggle
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.touchClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * First-run provider setup as a full-screen wizard (design Option 1): one step per
 * screen — Welcome -> Type -> Details -> Connecting/Done — with a
 * "1 Type · 2 Details · 3 Connect" indicator across the top. The step state lives in
 * [OnboardingViewModel] ([OnboardingStep]); this file is presentation only.
 *
 * Design system: no nav rail (no provider exists yet), a clock top-right, and a
 * remote-hint line at the bottom of every screen. Amber only on the one focused item;
 * "selected"/"on" states use white (§3.3).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onConnected: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.success) { if (state.success) onConnected() }

    // Back drives step-to-step; from WELCOME it returns false so the platform exits.
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { viewModel.back() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
        when (state.step) {
            OnboardingStep.WELCOME -> WelcomeScreen(onAdd = viewModel::goToType)
            OnboardingStep.TYPE -> TypeScreen(
                selected = state.type,
                onSelect = viewModel::selectType,
                onContinue = { viewModel.goToDetailsWith(state.type) },
                onBack = { viewModel.back() },
            )
            OnboardingStep.DETAILS -> DetailsScreen(state = state, vm = viewModel)
            OnboardingStep.CONNECTING, OnboardingStep.DONE ->
                ConnectingDoneScreen(state = state, onStart = viewModel::finish, onBack = { viewModel.back() })
        }
    }
}

// ─────────────────────────── Shared chrome ───────────────────────────

@Composable
private fun rememberClockLabel(): String = remember {
    SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(Date())
}

/** Brand wordmark (top-left) + clock (top-right). Welcome centres the wordmark instead. */
@Composable
private fun TopBar(showBrand: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (showBrand) Wordmark()
        Spacer(Modifier.weight(1f))
        Text(rememberClockLabel(), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** "Live" in onSurface + "Wire" in amber. Amber here is the brand dot exception (§3.1). */
@Composable
private fun Wordmark() {
    Row {
        Text("Live", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
        Text("Wire", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.Accent)
    }
}

/** A header row with the brand wordmark left and the clock right. */
@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        Text(rememberClockLabel(), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** The "1 Type · 2 Details · 3 Connect" indicator. Done steps show a check, current is white. */
@Composable
private fun StepIndicator(current: OnboardingStep) {
    // Map wizard steps onto the three visible dots.
    val activeIndex = when (current) {
        OnboardingStep.WELCOME, OnboardingStep.TYPE -> 0
        OnboardingStep.DETAILS -> 1
        OnboardingStep.CONNECTING, OnboardingStep.DONE -> 2
    }
    val labels = listOf("Type", "Details", "Connect")
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            if (i > 0) {
                Box(
                    Modifier.width(13.dp).height(1.dp)
                        .padding(horizontal = LiveWireDimens.SpaceS)
                        .background(LiveWireColors.Border),
                )
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
            }
            StepDot(index = i + 1, label = label, done = i < activeIndex, current = i == activeIndex)
            if (i < labels.lastIndex) Spacer(Modifier.width(LiveWireDimens.SpaceS))
        }
    }
}

@Composable
private fun StepDot(index: Int, label: String, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val filled = done || current
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(CircleShape)
                .background(if (filled) LiveWireColors.OnSurface else LiveWireColors.SurfaceRaised)
                .border(
                    LiveWireDimens.RestBorder,
                    if (done) LiveWireColors.BorderStrong else if (current) LiveWireColors.OnSurface else LiveWireColors.Border,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (filled) LiveWireColors.Canvas else LiveWireColors.OnSurfaceMuted,
            )
        }
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (current) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** The bottom remote-hint line, kept clear of all content at a fixed baseline. */
@Composable
private fun HintLine(vararg hints: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXl)) {
        hints.forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
                Spacer(Modifier.width(LiveWireDimens.SpaceXs))
                Text(v, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            }
        }
    }
}

/**
 * Page frame: safe-area padding, header (step indicator + clock), the [content], and a
 * bottom [hint] line at a fixed baseline. Fix #2: the hint line sits BELOW the content
 * column, never overlapping it.
 */
@Composable
private fun WizardScaffold(
    header: @Composable () -> Unit,
    hint: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
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
        header()
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        hint()
    }
}

// ─────────────────────────── Welcome ───────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WelcomeScreen(onAdd: () -> Unit) {
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { addFocus.requestFocus() } }
    WizardScaffold(
        header = { TopBar(showBrand = false) },
        hint = { HintLine("OK" to "Add a provider", "Back" to "Exit") },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Brand mark tile with amber spark.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiveWireColors.SurfaceRaised)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(LiveWireColors.Accent))
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Row {
                Text("Live", style = MaterialTheme.typography.displaySmall, color = LiveWireColors.OnSurface)
                Text("Wire", style = MaterialTheme.typography.displaySmall, color = LiveWireColors.Accent)
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text(
                "A player for your own IPTV provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
            Text(
                "No account, nothing leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXl))
            PrimaryButton(
                label = "Add a provider",
                onClick = onAdd,
                modifier = Modifier.focusRequester(addFocus),
            )
        }
    }
}

// ─────────────────────────── Type ───────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeScreen(
    selected: ProviderType,
    onSelect: (ProviderType) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val xtreamFocus = remember { FocusRequester() }
    val m3uFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { (if (selected == ProviderType.M3U) m3uFocus else xtreamFocus).requestFocus() }
    }
    WizardScaffold(
        header = { BrandHeader() },
        hint = { HintLine("◀ ▶" to "Choose type", "OK" to "Continue", "Back" to "Welcome") },
    ) {
        Column {
            StepIndicator(OnboardingStep.TYPE)
            Spacer(Modifier.height(LiveWireDimens.SpaceXl))
            Text("What kind of provider?", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            Text("Pick the format your provider gave you.", style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted)
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL), modifier = Modifier.widthIn(max = TypeCardsMaxWidth)) {
                TypeCard(
                    title = "Xtream Codes",
                    body = "A server address with a username and password.",
                    meta = "Server · Username · Password",
                    onClick = { onSelect(ProviderType.XTREAM); onContinue() },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(xtreamFocus)
                        .focusProperties { right = m3uFocus },
                    onFocused = { onSelect(ProviderType.XTREAM) },
                )
                TypeCard(
                    title = "M3U playlist",
                    body = "A single link to a .m3u file of channels.",
                    meta = "Playlist link",
                    onClick = { onSelect(ProviderType.M3U); onContinue() },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(m3uFocus)
                        .focusProperties { left = xtreamFocus },
                    onFocused = { onSelect(ProviderType.M3U) },
                )
            }
        }
    }
}

/**
 * A large provider-type card. Moving focus onto it selects that type (◀ ▶ switch),
 * matching the mockup's D-pad path; OK continues. Selection is shown by focus (the amber
 * ring), so there is no separate "selected" colour to fight the one-amber rule.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeCard(
    title: String,
    body: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.Surface,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
        modifier = modifier
            .heightIn(min = TypeCardMinHeight)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .touchClickable(onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(LiveWireDimens.SpaceL)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted)
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text(meta, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
        }
    }
}

// ─────────────────────────── Details ───────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailsScreen(state: OnboardingUiState, vm: OnboardingViewModel) {
    val isM3u = state.isM3u
    var showPassword by remember { mutableStateOf(false) }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val revealFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }

    // Column order: Name, Server(URL), then (Xtream) Username/Password or (M3U) Guide, then Connect.
    val afterUrl = if (isM3u) epgFocus else userFocus
    val beforeConnect = if (isM3u) epgFocus else passwordFocus

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    // Local field-level validation (shown after an attempt, or for a URL once typed).
    val urlNoun = if (isM3u) "playlist URL" else "server URL"
    val urlError = ProviderInputValidator.urlProblem(state.url, urlNoun)
        .takeIf { state.attempted || state.url.isNotBlank() }
    val epgError = ProviderInputValidator.urlProblem(state.epgUrl, "guide URL")
        .takeIf { isM3u && state.epgUrl.isNotBlank() }
    val userError = "Enter your username.".takeIf { !isM3u && state.attempted && state.username.isBlank() }
    val passwordError = "Enter your password.".takeIf { !isM3u && state.attempted && state.password.isEmpty() }

    WizardScaffold(
        header = { BrandHeader() },
        hint = { HintLine("▲ ▼" to "Move fields", "OK" to "Edit", "Back" to "Type") },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            StepIndicator(OnboardingStep.DETAILS)
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Text(
                if (isM3u) "M3U playlist details" else "Xtream Codes details",
                style = MaterialTheme.typography.headlineSmall,
                color = LiveWireColors.OnSurface,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            Text(
                "Enter what your provider sent you.",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceL))

            state.error?.let { ConnectErrorBanner(it) }

            Column(Modifier.widthIn(max = ProviderFormLayout.MaxWidth)) {
                // Row 1: Name | Server URL — one equal-width 2-column grid (fix #1).
                ProviderFormLayout.TwoColumnRow(
                    left = {
                        ProviderFilledField(
                            label = "Name", value = state.name, onValue = vm::setName,
                            focusRequester = nameFocus, upFocus = nameFocus, downFocus = afterUrl,
                            optional = true,
                            imeAction = ImeAction.Next, onImeAction = { runCatching { urlFocus.requestFocus() } },
                            modifier = Modifier.focusProperties { right = urlFocus },
                        )
                    },
                    right = {
                        ProviderFilledField(
                            label = if (isM3u) "Playlist URL" else "Server URL",
                            value = state.url, onValue = vm::setUrl,
                            focusRequester = urlFocus, upFocus = urlFocus, downFocus = afterUrl,
                            help = if (isM3u) "A link to your .m3u playlist file" else "You can paste the full link your provider sent",
                            error = urlError,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next, onImeAction = { runCatching { afterUrl.requestFocus() } },
                            modifier = Modifier.focusProperties { left = nameFocus },
                        )
                    },
                )

                if (isM3u) {
                    ProviderFilledField(
                        label = "Guide (XMLTV) URL", value = state.epgUrl, onValue = vm::setEpgUrl,
                        focusRequester = epgFocus, upFocus = urlFocus, downFocus = connectFocus,
                        optional = true,
                        help = "Leave blank to use the guide listed in the playlist.",
                        error = epgError,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done, onImeAction = { vm.connect() },
                    )
                } else {
                    // Row 2: Username | Password — same equal-width grid (fix #1).
                    ProviderFormLayout.TwoColumnRow(
                        left = {
                            ProviderFilledField(
                                label = "Username", value = state.username, onValue = vm::setUsername,
                                focusRequester = userFocus, upFocus = urlFocus, downFocus = connectFocus,
                                error = userError,
                                imeAction = ImeAction.Next, onImeAction = { runCatching { passwordFocus.requestFocus() } },
                                modifier = Modifier.focusProperties { right = passwordFocus },
                            )
                        },
                        right = {
                            ProviderFilledField(
                                label = "Password", value = state.password, onValue = vm::setPassword,
                                focusRequester = passwordFocus, upFocus = urlFocus, downFocus = connectFocus,
                                password = true, passwordVisible = showPassword,
                                error = passwordError,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done, onImeAction = { vm.connect() },
                                modifier = Modifier.focusProperties { left = userFocus; right = revealFocus },
                                trailing = {
                                    PasswordRevealToggle(
                                        visible = showPassword,
                                        onToggle = { showPassword = !showPassword },
                                        modifier = Modifier
                                            .focusRequester(revealFocus)
                                            .focusProperties { left = passwordFocus; up = urlFocus; down = connectFocus }
                                            .touchClickable { showPassword = !showPassword },
                                    )
                                },
                            )
                        },
                    )
                }

                Spacer(Modifier.height(LiveWireDimens.SpaceS))
                PrimaryButton(
                    label = "Connect",
                    onClick = vm::connect,
                    modifier = Modifier.focusRequester(connectFocus).focusProperties { up = beforeConnect },
                )
                Spacer(Modifier.height(LiveWireDimens.SpaceXl))
            }
        }
    }
}

/** The error banner above the form (design §3.3: onSurface text + a red alert icon). */
@Composable
private fun ConnectErrorBanner(error: ConnectError) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = ProviderFormLayout.MaxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
            .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
    ) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape)
                .background(LiveWireColors.SurfaceRaised)
                .border(LiveWireDimens.RestBorder, LiveWireColors.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.Live)
        }
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Column {
            Text(error.message, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
            error.detail?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            }
        }
    }
    Spacer(Modifier.height(LiveWireDimens.SpaceL))
}

// ─────────────────────────── Connecting / Done ───────────────────────────

/**
 * The final screen. While connecting (busy, no summary yet) it shows ONLY the step
 * list, centred. When the summary arrives it shows the success card with a compact
 * step-row above it (fix #4 — the "connecting then card" reading, chosen because it
 * fills the panel evenly at 1080p and never pins the button to the bottom). The
 * http:// cleartext warning is removed entirely (user decision).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectingDoneScreen(
    state: OnboardingUiState,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val done = state.step == OnboardingStep.DONE && state.summary != null
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(done) { if (done) runCatching { startFocus.requestFocus() } }

    WizardScaffold(
        header = { BrandHeader() },
        hint = {
            if (done) HintLine("OK" to "Start watching", "Back" to "Details")
            else HintLine("Back" to "Details")
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            StepIndicator(if (done) OnboardingStep.DONE else OnboardingStep.CONNECTING)
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            if (!done) {
                // Connecting: only the step list.
                Text("Setting up your provider", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
                Spacer(Modifier.height(LiveWireDimens.SpaceL))
                ConnectSteps(phase = state.connectPhase, compact = false)
            } else {
                // Done: compact step-row, then the success card.
                ConnectSteps(phase = ConnectPhase.LOADING_GUIDE, compact = true)
                Spacer(Modifier.height(LiveWireDimens.SpaceL))
                SuccessCard(summary = state.summary!!, startFocus = startFocus, onStart = onStart)
            }
        }
    }
}

/**
 * The three connecting sub-steps. [phase] marks the current one; earlier steps show a
 * check, the current shows a spinner glyph, later ones are muted. Guide loading is always
 * "continues in the background".
 */
@Composable
private fun ConnectSteps(phase: ConnectPhase, compact: Boolean) {
    val order = listOf(ConnectPhase.SIGNING_IN, ConnectPhase.LOADING_CHANNELS, ConnectPhase.LOADING_GUIDE)
    val currentIndex = order.indexOf(phase)
    val titles = mapOf(
        ConnectPhase.SIGNING_IN to "Signing in",
        ConnectPhase.LOADING_CHANNELS to "Loading channels",
        ConnectPhase.LOADING_GUIDE to "Loading guide",
    )
    Column(Modifier.widthIn(max = ProviderFormLayout.MaxWidth).fillMaxWidth()) {
        order.forEachIndexed { i, p ->
            val doneStep = i < currentIndex || (p == ConnectPhase.LOADING_GUIDE && phase == ConnectPhase.LOADING_GUIDE)
            val guideBg = p == ConnectPhase.LOADING_GUIDE
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 24.dp else 34.dp)
                    .padding(vertical = if (compact) 2.dp else LiveWireDimens.SpaceS),
            ) {
                StepGlyph(done = i < currentIndex, current = i == currentIndex && !guideBg, muted = i > currentIndex)
                Spacer(Modifier.width(LiveWireDimens.SpaceM))
                val title = titles.getValue(p)
                val muted = i > currentIndex || guideBg
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (muted) LiveWireColors.OnSurfaceMuted else LiveWireColors.OnSurface,
                )
                if (guideBg) {
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                    Text(
                        "continues in the background",
                        style = MaterialTheme.typography.labelMedium,
                        color = LiveWireColors.OnSurfaceMuted,
                    )
                } else if (i == currentIndex && !compact) {
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                    Text("In progress…", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
                } else if (i < currentIndex && !compact) {
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                    Text("Done", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
                }
            }
        }
    }
}

@Composable
private fun StepGlyph(done: Boolean, current: Boolean, muted: Boolean) {
    val filled = done || current
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (filled) LiveWireColors.OnSurface else LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, if (filled) LiveWireColors.OnSurface else LiveWireColors.Border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (done) "✓" else if (current) "…" else "•",
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) LiveWireColors.Canvas else LiveWireColors.OnSurfaceMuted,
        )
    }
}

/**
 * The success summary: an "Active" badge, the big live-channel count, then the facts
 * (expiry, connections — hidden when unknown), and a focused "Start watching" button a
 * normal distance below (fix #4). No http:// warning (user decision).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SuccessCard(summary: ProviderSummary, startFocus: FocusRequester, onStart: () -> Unit) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    Column(
        modifier = Modifier
            .widthIn(max = ProviderFormLayout.MaxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(LiveWireDimens.SpaceL),
    ) {
        // Active badge (white, never amber — §3.3). Hidden if status unknown.
        summary.status?.let { status ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(LiveWireColors.SurfaceFocused)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, RoundedCornerShape(50))
                    .padding(horizontal = LiveWireDimens.SpaceM, vertical = 4.dp),
            ) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = LiveWireColors.OnSurface)
                Spacer(Modifier.width(LiveWireDimens.SpaceXs))
                Text(status.uppercase(), style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
        }
        Text(
            formatCount(summary.liveChannels),
            style = MaterialTheme.typography.displaySmall,
            color = LiveWireColors.OnSurface,
        )
        Text("live channels ready to watch", style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted)

        val facts = buildList {
            summary.expiryEpochSeconds?.let { add("Expires" to formatExpiry(it)) }
            summary.maxConnections?.let { add("Connections" to it.toString()) }
        }
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXl)) {
                facts.forEach { (k, v) ->
                    Column {
                        Text(k.uppercase(), style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
                        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
                        Text(v, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
                    }
                }
            }
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        PrimaryButton(label = "Start watching", onClick = onStart, modifier = Modifier.focusRequester(startFocus))
    }
}

private fun formatCount(n: Int): String =
    "%,d".format(Locale.getDefault(), n)

private fun formatExpiry(epochSeconds: Long): String =
    runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
    }.getOrDefault("—")

// ─────────────────────────── Shared button ───────────────────────────

/** The one focused primary action (amber ring). Used for Add / Connect / Start watching. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
        modifier = modifier.touchClickable(onClick),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = LiveWireDimens.SpaceXl, vertical = LiveWireDimens.SpaceM),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
        }
    }
}

private val TypeCardsMaxWidth = 620.dp
private val TypeCardMinHeight = 130.dp
