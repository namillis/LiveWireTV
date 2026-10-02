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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.ui.provider.ProviderFieldLabelStyle
import com.livewire.tv.ui.provider.ProviderFilledField
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage IPTV providers, restyled to the design system as mockup Option 2.
 *
 * The list is a single focus column: each provider is ONE focusable [LiveWireSurface]
 * row (no per-row buttons), plus a dashed "Add provider" row. ▲ ▼ walk the column; OK on
 * a provider row opens a focus-trapped action menu (Set active · Edit · Delete), OK on
 * Add opens the full-page form. Delete opens a confirmation with Cancel focused. Back
 * closes the top overlay and returns focus to the row it came from; from the bare list,
 * Back falls through to the nav shell and pops to Settings.
 *
 * This is the fix for the old bug: Edit/Delete used to be buttons to the RIGHT of the
 * row, reachable only by D-pad Right or Tab — so on a real remote a provider could not be
 * deleted. Every action now lives in an overlay reachable with only ▲ ▼ OK Back.
 *
 * The left nav rail (with Settings lit) is drawn by the nav shell, which treats Providers
 * as a Settings sub-screen; content here starts at the same 48dp inset as Settings.
 *
 * Colour discipline (§3.3): amber only on the focus ring (via [LiveWireSurface]); the
 * ACTIVE mark and tags are neutral; Delete uses [LiveWireColors.Live] (red = destructive,
 * the one place red is allowed off the "live" meaning per the mockup).
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

    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    val addFocus = remember { FocusRequester() }
    fun focusFor(id: String) = rowFocus.getOrPut(id) { FocusRequester() }

    val firstFocus = remember { FocusRequester() }
    var firstHasFocus by remember { mutableStateOf(false) }
    val listActive = overlay is ProvidersOverlay.None
    // Where focus goes when the list becomes active: null = the first row (screen entry),
    // otherwise the row or Add item an overlay was opened from. Requested from this
    // effect, not from closeOverlayTo, because closing the full-page form recomposes the
    // list and a request made before the target row exists is silently dropped.
    var restoreTo by remember { mutableStateOf<FocusOrigin?>(null) }
    LaunchedEffect(state.providers, listActive, restoreTo) {
        if (!listActive || state.providers.isEmpty()) return@LaunchedEffect
        val target = when (val r = restoreTo) {
            is FocusOrigin.Row ->
                if (state.providers.any { it.id == r.providerId }) focusFor(r.providerId) else firstFocus
            FocusOrigin.Add -> addFocus
            null -> firstFocus
        }
        if (target === firstFocus) {
            var held = 0
            repeat(30) {
                if (firstHasFocus) held++ else {
                    held = 0
                    runCatching { firstFocus.requestFocus() }
                }
                if (held >= 3) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        } else {
            // A few attempts cover the frames it takes the list to come back after the form.
            repeat(6) {
                runCatching { target.requestFocus() }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    fun closeOverlayTo(next: ProvidersOverlay) {
        overlay = next
        if (next is ProvidersOverlay.None) restoreTo = origin
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The Form overlay is a FULL-PAGE screen (mockup option1-edit), not a dialog —
            // it replaces the list rather than dimming it. Everything else is the list plus
            // a scrim dialog.
            val formOverlay = overlay as? ProvidersOverlay.Form
            if (formOverlay != null) {
                ProviderFormPage(
                    initial = formOverlay.editing,
                    validating = state.validating,
                    error = state.formError,
                    onSubmit = { draft ->
                        viewModel.addOrUpdate(formOverlay.editing?.id, draft) {
                            closeOverlayTo(ProvidersOverlayRules.close())
                        }
                    },
                    onCancel = {
                        viewModel.clearFormError()
                        closeOverlayTo(ProvidersOverlayRules.back(formOverlay))
                    },
                )
            } else {
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
                    TopLine(title = "Providers", sub = "${state.providers.size} configured")
                    Spacer(Modifier.height(LiveWireDimens.SpaceXs))
                    Breadcrumb("Settings", "Providers")
                    Spacer(Modifier.height(LiveWireDimens.SpaceM))
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
                    HintLine(overlay)
                }

                when (val o = overlay) {
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
                    else -> Unit
                }
            }
        }
    }
}

/** Page title (display font) + a mono sub-label, and a live clock on the right. */
@Composable
private fun TopLine(title: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Text(
            sub,
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.padding(bottom = 2.dp).weight(1f),
        )
        Text(rememberClockLabel(), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

/** A mono breadcrumb "a › b › …" in muted text. */
@Composable
private fun Breadcrumb(vararg parts: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            if (i > 0) {
                Text("  ›  ", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
            }
            Text(p, style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun rememberClockLabel(): String = remember {
    SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(Date())
}

/**
 * The provider rows plus the Add row, in one capped-width focus column left-aligned to the
 * title (no left content inset, so rows share the title's edge — mockup point 2). ▼/▲ walk.
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
        // Left inset 0 so rows align with the title; small other-side insets keep the wide
        // focus ring/scale off the edges.
        contentPadding = PaddingValues(start = 0.dp, end = RingInset, top = RingInset, bottom = RingInset),
    ) {
        itemsIndexed(providers, key = { _, p -> p.id }) { index, p ->
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
 * One provider = one focusable row. Left: name + an ACTIVE pill when active, then a mono
 * type tag and a host·user summary. Right: the ⋯ affordance, and — only while the row is
 * focused — an "OK · Actions" hint beside a white-filled ⋯. Nothing here is separately
 * focusable; OK on the row opens the menu.
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
    var focused by remember { mutableStateOf(false) }
    LiveWireSurface(
        onClick = onOpenMenu,
        restingColor = LiveWireColors.Surface,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            },
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
                        color = if (focused) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // "OK · Actions" appears only on the focused row (mockup); unfocused rows show
            // just the ⋯ button.
            if (focused) {
                Text(
                    "OK · Actions",
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(LiveWireDimens.SpaceM))
            }
            MoreAffordance(focused = focused)
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
            .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
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

/**
 * The ⋯ affordance. Raised neutral when the row is at rest; a white fill with dark dots
 * when the row is focused (mockup). Presentation only.
 */
@Composable
private fun MoreAffordance(focused: Boolean) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 32.dp)
            .clip(shape)
            .background(if (focused) LiveWireColors.OnSurface else LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, if (focused) LiveWireColors.OnSurface else LiveWireColors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "•••",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (focused) LiveWireColors.Canvas else LiveWireColors.OnSurface,
        )
    }
}

/** The dashed "+ Add provider" row (mockup): compact, centered. OK opens the empty form. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProviderRow(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    LiveWireSurface(
        onClick = onOpen,
        // Transparent at rest with a dashed-look strong border; the focus ring still comes
        // from LiveWireSurface. (Compose has no dashed border token, so a hairline strong
        // border stands in for the mockup's dashed edge.)
        restingColor = Color.Transparent,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onFocusChanged != null) Modifier.onFocusChanged { onFocusChanged(it.isFocused) } else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AddRowHeight)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            Text("Add provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = LiveWireColors.OnSurface)
        }
    }
}

/** Host and type only: full URLs and playlist links can carry credentials. */
private fun providerSummary(p: ProviderConfig): String = when (p.type) {
    ProviderType.XTREAM -> "${p.displayHost()}  ·  ${p.username}"
    ProviderType.M3U -> p.displayHost()
}

// ─────────────────────────── Action menu / confirmation ───────────────────────────

/**
 * Shared dialog scaffold: a scrim over the dimmed list with a centred card. Back is
 * [onBack]. The card's focusable children are trapped inside because they are the only
 * focus targets composed while the overlay is open.
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
                .padding(DialogPadding),
            content = content,
        )
    }
}

/** Dialog heading (display font) + a tag·host sub-row (no username in the menu header). */
@Composable
private fun MenuHeader(provider: ProviderConfig) {
    Text(provider.name, style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(LiveWireDimens.SpaceXs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeTag(provider.type)
        Spacer(Modifier.width(LiveWireDimens.SpaceS))
        Text(provider.displayHost(), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The three action glyphs (mockup): check-circle, pencil, trash. */
private enum class MenuIcon { CHECK_CIRCLE, PENCIL, TRASH }

/**
 * The action menu (mockup option2-edit): Set active · Edit · Delete, each with its icon.
 * Delete is red with a "confirms next" hint. On the active provider "Set active" is
 * dropped. First item focused on open; Back closes to the list.
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
    DialogScaffold(onBack = onBack) {
        MenuHeader(provider)
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        if (!isActive) {
            MenuItem("Set active", MenuIcon.CHECK_CIRCLE, onClick = onSetActive, modifier = Modifier.focusRequester(first))
            Spacer(Modifier.height(MenuGap))
        }
        MenuItem(
            "Edit", MenuIcon.PENCIL, onClick = onEdit,
            modifier = if (isActive) Modifier.focusRequester(first) else Modifier,
        )
        Spacer(Modifier.height(MenuGap))
        MenuItem("Delete", MenuIcon.TRASH, onClick = onDelete, danger = true, trailing = "confirms next")
    }
}

/**
 * One full-width menu row inside a dialog: leading glyph, label, optional trailing hint.
 * [danger] paints the glyph and label red (Delete).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MenuItem(
    label: String,
    icon: MenuIcon?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    trailing: String? = null,
) {
    val fg = if (danger) LiveWireColors.Live else LiveWireColors.OnSurface
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
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                MenuGlyph(icon, tint = fg)
                Spacer(Modifier.width(LiveWireDimens.SpaceM))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
            }
        }
    }
}

/** A simple text-glyph stand-in for the mockup's line icons (no icon asset dependency). */
@Composable
private fun MenuGlyph(icon: MenuIcon, tint: Color) {
    // Line icons drawn from vector drawables, tinted at runtime — the same approach the nav
    // rail uses (painterResource + Icon), not emoji.
    val res = when (icon) {
        MenuIcon.CHECK_CIRCLE -> R.drawable.ic_action_set_active
        MenuIcon.PENCIL -> R.drawable.ic_action_edit
        MenuIcon.TRASH -> R.drawable.ic_action_delete
    }
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * Delete confirmation, same card style. Cancel is focused by default (destructive action
 * is never the default target). Back / Cancel returns to the action menu.
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
    DialogScaffold(onBack = onCancel) {
        Text("Delete ${provider.name}?", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Text("This removes the provider from this device.", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
        Spacer(Modifier.height(LiveWireDimens.SpaceL))
        MenuItem("Cancel", icon = null, onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus))
        Spacer(Modifier.height(MenuGap))
        MenuItem("Delete", MenuIcon.TRASH, onClick = onConfirm, danger = true)
    }
}

// ─────────────────────────── Full-page form ───────────────────────────

/**
 * The add/edit form as a FULL-PAGE screen (mockup option1-edit): title "Add provider" /
 * "Edit provider" with the provider name in mono, a "Settings › Providers › Edit"
 * breadcrumb, the Xtream/M3U segmented toggle (shown for edit too — locked to the saved
 * type), filled fields with uppercase mono overline labels, Username+Password side by
 * side, then compact Save / Cancel. Same field set and validation path as before; type is
 * fixed once saved. ▲ ▼ walk toggle → fields → Save/Cancel; Back cancels.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderFormPage(
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
    val editing = initial != null

    val xtreamFocus = remember { FocusRequester() }
    val m3uFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }

    // The toggle is a focus stop only when adding (type editable). When editing it is a
    // locked display row, so the first field's Up target is itself (stays put).
    val typeUp = when { !editing && isM3u -> m3uFocus; !editing -> xtreamFocus; else -> nameFocus }
    val afterUrl = if (isM3u) epgFocus else userFocus
    val lastField = if (isM3u) epgFocus else passFocus

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    fun submit() {
        if (!validating) {
            onSubmit(ProviderDraft(type = type, name = name, url = url, username = user, password = pass, epgUrl = epg))
        }
    }

    BackHandler(enabled = true, onBack = onCancel)

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
        TopLine(
            title = if (editing) "Edit provider" else "Add provider",
            sub = if (editing) initial!!.name else "New",
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))
        Breadcrumb("Settings", "Providers", if (editing) "Edit" else "Add")
        Spacer(Modifier.height(LiveWireDimens.SpaceM))

        Column(modifier = Modifier.widthIn(max = FormMaxWidth).weight(1f)) {
            // Segmented type toggle. On Add it is a focusable Xtream|M3U choice; on Edit it
            // is a locked, non-focusable row showing the saved type (mockup shows it too).
            TypeToggle(
                type = type,
                editable = !editing,
                onXtream = { type = ProviderType.XTREAM },
                onM3u = { type = ProviderType.M3U },
                xtreamFocus = xtreamFocus,
                m3uFocus = m3uFocus,
                downFocus = nameFocus,
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceL))

            FilledField(
                label = "Name",
                value = name, onValue = { name = it },
                focusRequester = nameFocus,
                upFocus = typeUp, downFocus = urlFocus,
                imeAction = ImeAction.Next, onImeAction = { runCatching { urlFocus.requestFocus() } },
            )
            FilledField(
                label = if (isM3u) "Playlist URL" else "Server URL",
                value = url, onValue = { url = it },
                focusRequester = urlFocus,
                upFocus = nameFocus, downFocus = afterUrl,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next, onImeAction = { runCatching { afterUrl.requestFocus() } },
            )
            if (isM3u) {
                FilledField(
                    label = "Guide (XMLTV) URL — optional",
                    value = epg, onValue = { epg = it },
                    focusRequester = epgFocus,
                    upFocus = urlFocus, downFocus = submitFocus,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done, onImeAction = { submit() },
                )
            } else {
                // Username and Password side by side (mockup .cols).
                Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL)) {
                    Box(Modifier.weight(1f)) {
                        FilledField(
                            label = "Username",
                            value = user, onValue = { user = it },
                            focusRequester = userFocus,
                            upFocus = urlFocus, downFocus = submitFocus,
                            imeAction = ImeAction.Next, onImeAction = { runCatching { passFocus.requestFocus() } },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        FilledField(
                            label = "Password",
                            value = pass, onValue = { pass = it },
                            focusRequester = passFocus,
                            upFocus = urlFocus, downFocus = submitFocus,
                            password = true,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done, onImeAction = { submit() },
                        )
                    }
                }
            }

            val usesHttp = url.trim().startsWith("http://", ignoreCase = true) ||
                (isM3u && epg.trim().startsWith("http://", ignoreCase = true))
            if (usesHttp) {
                Spacer(Modifier.height(LiveWireDimens.SpaceXs))
                Text(
                    "This provider uses unencrypted HTTP. Use only a trusted network.",
                    color = LiveWireColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let {
                Spacer(Modifier.height(LiveWireDimens.SpaceXs))
                Text(it, color = LiveWireColors.Live, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(LiveWireDimens.SpaceL))
            Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM)) {
                FormButton(
                    label = if (validating) "Validating…" else if (editing) "Save" else "Add",
                    onClick = { submit() },
                    modifier = Modifier.focusRequester(submitFocus).focusProperties { up = lastField; right = cancelFocus },
                )
                FormButton(
                    label = "Cancel",
                    ghost = true,
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus).focusProperties { up = lastField; left = submitFocus },
                )
            }
        }

        HintLine(ProvidersOverlay.Form(initial))
    }
}

/**
 * The segmented Xtream|M3U toggle. When [editable] each option is a focusable button
 * (Add flow); otherwise it is a locked display row (Edit flow) with the saved type marked.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeToggle(
    type: ProviderType,
    editable: Boolean,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
    xtreamFocus: FocusRequester,
    m3uFocus: FocusRequester,
    downFocus: FocusRequester,
) {
    val isM3u = type == ProviderType.M3U
    if (editable) {
        Row {
            SegmentButton("✓ Xtream Codes".takeIf { !isM3u } ?: "Xtream Codes", selected = !isM3u, onClick = onXtream,
                modifier = Modifier.focusRequester(xtreamFocus).focusProperties { right = m3uFocus; down = downFocus })
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            SegmentButton("✓ M3U playlist".takeIf { isM3u } ?: "M3U playlist", selected = isM3u, onClick = onM3u,
                modifier = Modifier.focusRequester(m3uFocus).focusProperties { left = xtreamFocus; down = downFocus })
        }
    } else {
        // Locked: a segmented look with the saved type filled, not focusable.
        val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
        Row(modifier = Modifier.clip(shape).border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)) {
            LockedSegment("Xtream Codes", selected = !isM3u, leading = !isM3u)
            Box(Modifier.width(1.dp).height(ControlHeight).background(LiveWireColors.Border))
            LockedSegment("M3U playlist", selected = isM3u, leading = isM3u)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = if (selected) LiveWireColors.SurfaceFocused else LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier,
    ) {
        Box(Modifier.heightIn(min = ControlHeight).padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LockedSegment(label: String, selected: Boolean, leading: Boolean) {
    Box(
        modifier = Modifier
            .background(if (selected) LiveWireColors.SurfaceFocused else LiveWireColors.SurfaceRaised)
            .heightIn(min = ControlHeight)
            .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading) {
                Text("✓", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
                Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A FILLED field (mockup): a small uppercase mono overline label, then a
 * filled input. Delegates to the shared [ProviderFilledField] so the Providers form
 * and onboarding stay identical; the input keeps the D-pad focus chaining (up/down
 * focusProperties + dpadVerticalExit) so the whole form is one focus column.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilledField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    downFocus: FocusRequester,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    ProviderFilledField(
        label = label,
        value = value,
        onValue = onValue,
        focusRequester = focusRequester,
        upFocus = upFocus,
        downFocus = downFocus,
        modifier = modifier,
        password = password,
        labelStyle = ProviderFieldLabelStyle.OVERLINE,
        keyboardType = keyboardType,
        imeAction = imeAction,
        onImeAction = onImeAction,
    )
}

/** A compact form button. [ghost] is the muted Cancel variant. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, ghost: Boolean = false) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = modifier,
    ) {
        Box(Modifier.heightIn(min = ControlHeight).padding(horizontal = LiveWireDimens.SpaceXl, vertical = LiveWireDimens.SpaceM), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (ghost) FontWeight.SemiBold else FontWeight.Bold,
                color = if (ghost) LiveWireColors.OnSurfaceMuted else LiveWireColors.OnSurface,
                maxLines = 1,
            )
        }
    }
}

// ─────────────────────────── Hint line ───────────────────────────

/** The bottom remote-hint line; its keys change with the current overlay (mockups). */
@Composable
private fun HintLine(overlay: ProvidersOverlay) {
    val hints: List<Pair<String, String>> = when (overlay) {
        is ProvidersOverlay.Menu -> listOf("▲ ▼" to "Choose action", "OK" to "Select", "Back" to "Close menu")
        is ProvidersOverlay.ConfirmDelete -> listOf("▲ ▼" to "Choose", "OK" to "Select", "Back" to "Cancel")
        is ProvidersOverlay.Form -> listOf("▲ ▼" to "Fields", "OK" to "Edit / Save", "Back" to "Cancel")
        is ProvidersOverlay.None -> listOf("▲ ▼" to "Move rows", "OK" to "Open actions", "Back" to "Settings")
    }
    Row(
        modifier = Modifier.padding(top = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
    ) {
        hints.forEach { (k, v) -> Hint(k, v) }
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
private val RowGap = 8.dp
private val RowMinHeight = 56.dp
private val AddRowHeight = 40.dp
private val DialogMaxWidth = 340.dp
private val DialogPadding = 20.dp
private val MenuRowHeight = 44.dp
private val MenuGap = 8.dp
private val FormMaxWidth = 620.dp
private val ControlHeight = 36.dp
private val RingInset = LiveWireDimens.SpaceS
