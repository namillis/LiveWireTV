package com.livewire.tv.feature.providers

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.feature.onboarding.ProviderTypeButton
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit
import com.livewire.tv.ui.theme.liveWireTextFieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage IPTV providers, restyled to the design system as mockup Option 2.
 *
 * The list is a single focus column: each provider is ONE focusable [LiveWireSurface]
 * row (no per-row buttons), plus an "Add provider" row at the end. ▲ ▼ walk the column;
 * OK on a provider row opens a focus-trapped action menu (Set active · Edit · Delete),
 * OK on Add opens the form. Delete opens a confirmation with Cancel focused. Back closes
 * the top overlay and returns focus to the row it came from; from the bare list, Back
 * falls through to the NavHost and returns to Settings (mockup breadcrumb / hint).
 *
 * This is the fix for the old bug: Edit/Delete used to be buttons to the RIGHT of the
 * row, reachable only by D-pad Right or Tab — so on a real remote a provider could not
 * be deleted. Every action now lives in an overlay reachable with only ▲ ▼ OK Back.
 *
 * Colour discipline (§3.3): amber only on the focus ring (via [LiveWireSurface]); the
 * ACTIVE mark and tags are neutral. Sizing follows Settings' density-2.0 tokens so the
 * screen matches option2.png at 1080p. ViewModel/repository behaviour is unchanged.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf<ProvidersOverlay>(ProvidersOverlay.None) }
    var origin by remember { mutableStateOf<FocusOrigin>(FocusOrigin.Add) }

    LaunchedEffect(Unit) { viewModel.load() }

    // One FocusRequester per provider row plus the Add row, so closing an overlay can
    // return focus to exactly the item it came from. Keyed by id; Add has its own.
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    val addFocus = remember { FocusRequester() }
    fun focusFor(id: String) = rowFocus.getOrPut(id) { FocusRequester() }

    // Focus the first row (or Add, when empty) on open, so the first D-pad press acts on
    // the list — the same first-focus pattern Settings/Home use.
    val firstFocus = remember { FocusRequester() }
    var firstHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.providers.isNotEmpty(), overlay is ProvidersOverlay.None) {
        if (overlay !is ProvidersOverlay.None) return@LaunchedEffect
        var held = 0
        repeat(30) {
            if (firstHasFocus) held++ else {
                held = 0
                runCatching { firstFocus.requestFocus() }
            }
            if (held >= 3) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    // Return focus to the origin item once every overlay has closed.
    fun closeOverlayTo(next: ProvidersOverlay) {
        overlay = next
        if (next is ProvidersOverlay.None) {
            runCatching {
                when (val o = origin) {
                    is FocusOrigin.Row -> focusFor(o.providerId).requestFocus()
                    FocusOrigin.Add -> addFocus.requestFocus()
                }
            }
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
                TopLine(count = state.providers.size)
                Spacer(Modifier.height(LiveWireDimens.SpaceL))
                ProviderList(
                    providers = state.providers,
                    activeId = state.activeId,
                    firstFocus = firstFocus,
                    onFirstFocusChanged = { firstHasFocus = it },
                    focusFor = ::focusFor,
                    addFocus = addFocus,
                    onOpenMenu = { p ->
                        val (next, o) = ProvidersOverlayRules.openMenu(p)
                        origin = o; overlay = next
                    },
                    onOpenAdd = {
                        val (next, o) = ProvidersOverlayRules.openAdd()
                        origin = o; overlay = next
                    },
                    modifier = Modifier.weight(1f),
                )
                HintLine()
            }

            when (val o = overlay) {
                is ProvidersOverlay.None -> Unit
                is ProvidersOverlay.Menu -> ActionMenuOverlay(
                    provider = o.provider,
                    isActive = o.provider.id == state.activeId,
                    onSetActive = { viewModel.setActive(o.provider.id); closeOverlayTo(ProvidersOverlayRules.close()) },
                    onEdit = { overlay = ProvidersOverlayRules.editFromMenu(o.provider) },
                    onDelete = { overlay = ProvidersOverlayRules.confirmDeleteFromMenu(o.provider) },
                    onBack = { closeOverlayTo(ProvidersOverlayRules.back(o)) },
                )
                is ProvidersOverlay.ConfirmDelete -> ConfirmDeleteOverlay(
                    provider = o.provider,
                    onCancel = { closeOverlayTo(ProvidersOverlayRules.back(o)) },
                    onConfirm = { viewModel.remove(o.provider.id); closeOverlayTo(ProvidersOverlayRules.close()) },
                )
                is ProvidersOverlay.Form -> FormOverlay(
                    initial = o.editing,
                    validating = state.validating,
                    error = state.formError,
                    onSubmit = { draft ->
                        viewModel.addOrUpdate(o.editing?.id, draft) {
                            closeOverlayTo(ProvidersOverlayRules.close())
                        }
                    },
                    onCancel = {
                        viewModel.clearFormError()
                        closeOverlayTo(ProvidersOverlayRules.back(o))
                    },
                )
            }
        }
    }
}

/**
 * Page title + a "N configured" count and a "Settings › Providers" breadcrumb on the
 * left, a live clock on the right — the mockup top row, matching Settings' [TopLine].
 */
@Composable
private fun TopLine(count: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Providers",
                style = MaterialTheme.typography.headlineSmall,
                color = LiveWireColors.OnSurface,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
            Text(
                "$count configured",
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
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Settings",
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
            )
            Text(
                "  ›  Providers",
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun rememberClockLabel(): String = remember {
    SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(Date())
}

/**
 * The provider rows plus the Add row, in one capped-width focus column (~62% of the
 * content area, left-aligned to the title, like Settings' list). ▼/▲ walk the rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderList(
    providers: List<ProviderConfig>,
    activeId: String?,
    firstFocus: FocusRequester,
    onFirstFocusChanged: (Boolean) -> Unit,
    focusFor: (String) -> FocusRequester,
    addFocus: FocusRequester,
    onOpenMenu: (ProviderConfig) -> Unit,
    onOpenAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.widthIn(max = ListMaxWidth),
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(
            start = RingInset, end = RingInset, top = RingInset, bottom = RingInset,
        ),
    ) {
        itemsIndexed(providers, key = { _, p -> p.id }) { index, p ->
            // The first provider row owns the initial-focus requester.
            val fr = focusFor(p.id)
            ProviderRow(
                provider = p,
                isActive = p.id == activeId,
                onOpenMenu = { onOpenMenu(p) },
                modifier = Modifier
                    .focusRequester(fr)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                onFocusChanged = if (index == 0) onFirstFocusChanged else null,
            )
        }
        item("add") {
            AddProviderRow(
                onOpen = onOpenAdd,
                modifier = Modifier
                    .focusRequester(addFocus)
                    .then(if (providers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                onFocusChanged = if (providers.isEmpty()) onFirstFocusChanged else null,
            )
        }
    }
}

/**
 * One provider = one focusable row (mockup: whole card is the focus target). Left: name
 * + an ACTIVE pill when active, then a mono type tag and a host·user summary. Right: an
 * "OK · Actions" hint and a ⋯ affordance — presentation only; OK on the row opens the
 * menu. The ring/scale come from [LiveWireSurface]; nothing here is separately focusable.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    isActive: Boolean,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    LiveWireSurface(
        onClick = onOpenMenu,
        restingColor = LiveWireColors.Surface,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onFocusChanged != null) {
                    Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = LiveWireDimens.SpaceM)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = LiveWireColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(LiveWireDimens.SpaceS))
                        ActivePill()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeTag(provider.type)
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                    Text(
                        providerSummary(provider),
                        style = MaterialTheme.typography.labelMedium,
                        color = LiveWireColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "OK · Actions",
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
            MoreAffordance()
        }
    }
}

/** The neutral "✓ ACTIVE" pill on the active row (never amber; §3.3). */
@Composable
private fun ActivePill() {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(LiveWireColors.SurfaceFocused)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Text("ACTIVE", style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
    }
}

/** A mono type tag: XTREAM or M3U (mockup's small bordered chip). */
@Composable
private fun TypeTag(type: ProviderType) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 2.dp),
    ) {
        Text(
            when (type) { ProviderType.XTREAM -> "XTREAM"; ProviderType.M3U -> "M3U" },
            style = LiveWireTheme.tokens.tag,
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}

/** The ⋯ affordance on the right of a row (mockup). Presentation only. */
@Composable
private fun MoreAffordance() {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 26.dp)
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("•••", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
    }
}

/** The "+ Add provider" row at the end of the list (mockup). OK opens the empty form. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProviderRow(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    LiveWireSurface(
        onClick = onOpen,
        restingColor = LiveWireColors.Surface,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onFocusChanged != null) {
                    Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            Text(
                "Add provider",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LiveWireColors.OnSurface,
            )
        }
    }
}

/** Host and type only: full URLs and playlist links can carry credentials. */
private fun providerSummary(p: ProviderConfig): String = when (p.type) {
    ProviderType.XTREAM -> "${p.displayHost()}  ·  ${p.username}"
    ProviderType.M3U -> p.displayHost()
}

// ─────────────────────────── Overlays ───────────────────────────

/**
 * Shared overlay chrome: a dimmed scrim over the whole screen with a centred dialog card
 * (§8: content behind is dimmed, never blurred). Back is handled by [onBack]. The card is
 * a plain surface, not focusable itself; its focusable children are trapped inside because
 * they are the only focus targets composed while the overlay is open.
 */
@Composable
private fun OverlayScaffold(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveWireColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(LiveWireDimens.RadiusDialog)
        Column(
            modifier = Modifier
                .widthIn(max = DialogMaxWidth)
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(shape)
                .background(LiveWireColors.Surface)
                .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                .padding(LiveWireDimens.SpaceL),
        ) {
            content()
        }
    }
}

/** A dialog heading + optional one-line subtitle. */
@Composable
private fun DialogHeader(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The action menu (mockup Option 2): Set active · Edit · Delete, stacked as focusable
 * rows. On the active provider "Set active" is dropped (nothing to do). First item is
 * focused on open. Back closes to the list.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionMenuOverlay(
    provider: ProviderConfig,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayScaffold(onBack = onBack) {
        DialogHeader(provider.name, providerSummary(provider))
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        if (!isActive) {
            MenuItem("Set active", onClick = onSetActive, modifier = Modifier.focusRequester(first))
            Spacer(Modifier.height(MenuGap))
        }
        MenuItem(
            "Edit",
            onClick = onEdit,
            modifier = if (isActive) Modifier.focusRequester(first) else Modifier,
        )
        Spacer(Modifier.height(MenuGap))
        MenuItem("Delete", onClick = onDelete)
    }
}

/** One full-width menu row inside a dialog. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MenuRowHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
        }
    }
}

/**
 * Delete confirmation, styled like the menu. Cancel is focused by default (destructive
 * action is never the default target). Back / Cancel returns to the action menu.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteOverlay(
    provider: ProviderConfig,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    OverlayScaffold(onBack = onCancel) {
        DialogHeader("Delete ${provider.name}?", "This removes the provider from this device.")
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        // Cancel first and focused: the safe choice is the default target.
        MenuItem("Cancel", onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus))
        Spacer(Modifier.height(MenuGap))
        MenuItem("Delete", onClick = onConfirm)
    }
}

/**
 * The add/edit form as an overlay: fields top to bottom, then Save and Cancel (mockup
 * option2-edit). Same field set and validation path as before; type is fixed once saved.
 * The name field is focused on open so a remote user lands in the form.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormOverlay(
    initial: ProviderConfig?,
    validating: Boolean,
    error: String?,
    onSubmit: (ProviderDraft) -> Unit,
    onCancel: () -> Unit,
) {
    val start = initial?.let(ProviderDraft::from)
    var type by remember { mutableStateOf(start?.type ?: ProviderType.XTREAM) }
    var name by remember { mutableStateOf(start?.name ?: ProviderDraft.DEFAULT_NAME) }
    var url by remember { mutableStateOf(start?.url.orEmpty()) }
    var user by remember { mutableStateOf(start?.username.orEmpty()) }
    var pass by remember { mutableStateOf(start?.password.orEmpty()) }
    var epg by remember { mutableStateOf(start?.epgUrl.orEmpty()) }
    val isM3u = type == ProviderType.M3U

    // Focus targets so the whole form is walkable with ▲ ▼ on a remote. OutlinedTextField
    // otherwise keeps D-pad Up/Down for the cursor and strands focus, so every field wires
    // both focusProperties (D-pad) and dpadVerticalExit (soft-D-pad key sources) — the same
    // pattern onboarding uses.
    val xtreamFocus = remember { FocusRequester() }
    val m3uFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }

    // Where a field's Down goes and where the buttons' Up goes: the last field before them.
    val typeUp = if (isM3u) m3uFocus else xtreamFocus
    val afterUrl = if (isM3u) epgFocus else userFocus
    val lastField = if (isM3u) epgFocus else passFocus

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    fun submit() {
        if (!validating) {
            onSubmit(
                ProviderDraft(
                    type = type, name = name, url = url,
                    username = user, password = pass, epgUrl = epg,
                ),
            )
        }
    }

    OverlayScaffold(onBack = onCancel) {
        DialogHeader(if (initial == null) "Add provider" else "Edit ${initial.name}")
        Spacer(Modifier.height(LiveWireDimens.SpaceM))

        val fm = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        val colors = liveWireTextFieldColors()

        // Type is fixed once saved: switching would discard credentials or the playlist.
        if (initial == null) {
            Row(modifier = Modifier.padding(bottom = LiveWireDimens.SpaceS)) {
                ProviderTypeButton(
                    "Xtream Codes", selected = !isM3u,
                    onClick = { type = ProviderType.XTREAM },
                    modifier = Modifier
                        .padding(end = LiveWireDimens.SpaceS)
                        .focusRequester(xtreamFocus)
                        .focusProperties { right = m3uFocus; down = nameFocus },
                )
                ProviderTypeButton(
                    "M3U playlist", selected = isM3u,
                    onClick = { type = ProviderType.M3U },
                    modifier = Modifier
                        .focusRequester(m3uFocus)
                        .focusProperties { left = xtreamFocus; down = nameFocus },
                )
            }
        }

        OutlinedTextField(
            name, { name = it }, label = { Text("Name") }, singleLine = true, colors = colors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { runCatching { urlFocus.requestFocus() } }),
            modifier = fm
                .focusRequester(nameFocus)
                .focusProperties { up = if (initial == null) typeUp else submitFocus; down = urlFocus }
                .dpadVerticalExit(up = if (initial == null) typeUp else null, down = urlFocus),
        )
        OutlinedTextField(
            url, { url = it },
            label = { Text(if (isM3u) "Playlist URL" else "Server URL (http://host:port)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { runCatching { afterUrl.requestFocus() } }),
            colors = colors,
            modifier = fm
                .focusRequester(urlFocus)
                .focusProperties { up = nameFocus; down = afterUrl }
                .dpadVerticalExit(up = nameFocus, down = afterUrl),
        )
        if (isM3u) {
            OutlinedTextField(
                epg, { epg = it },
                label = { Text("Guide (XMLTV) URL, optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = colors,
                modifier = fm
                    .focusRequester(epgFocus)
                    .focusProperties { up = urlFocus; down = submitFocus }
                    .dpadVerticalExit(up = urlFocus, down = submitFocus),
            )
        } else {
            OutlinedTextField(
                user, { user = it }, label = { Text("Username") }, singleLine = true, colors = colors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { runCatching { passFocus.requestFocus() } }),
                modifier = fm
                    .focusRequester(userFocus)
                    .focusProperties { up = urlFocus; down = passFocus }
                    .dpadVerticalExit(up = urlFocus, down = passFocus),
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = colors,
                modifier = fm
                    .focusRequester(passFocus)
                    .focusProperties { up = userFocus; down = submitFocus }
                    .dpadVerticalExit(up = userFocus, down = submitFocus),
            )
        }
        val usesHttp = url.trim().startsWith("http://", ignoreCase = true) ||
            (isM3u && epg.trim().startsWith("http://", ignoreCase = true))
        if (usesHttp) {
            Text(
                "This provider uses unencrypted HTTP. Use only a trusted network.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = fm,
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = LiveWireDimens.SpaceS))
        }
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Row {
            MenuItem(
                if (validating) "Validating…" else if (initial == null) "Add" else "Save",
                onClick = { submit() },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(submitFocus)
                    .focusProperties { up = lastField; right = cancelFocus },
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            MenuItem(
                "Cancel",
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocus)
                    .focusProperties { up = lastField; left = submitFocus },
            )
        }
    }
}

/** The bottom remote-hint line (mockup): ▲▼ Move rows · OK Open actions · Back Settings. */
@Composable
private fun HintLine() {
    Row(
        modifier = Modifier.padding(top = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
    ) {
        Hint("▲ ▼", "Move rows")
        Hint("OK", "Open actions")
        Hint("Back", "Settings")
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

// ── Layout constants tuned to the mockup at density 2.0 (1920px ≈ 960dp). ──
private val ListMaxWidth = 620.dp
private val RowGap = 10.dp
private val RowMinHeight = 56.dp
private val DialogMaxWidth = 440.dp
private val MenuRowHeight = 40.dp
private val MenuGap = 8.dp
private val RingInset = LiveWireDimens.SpaceS
