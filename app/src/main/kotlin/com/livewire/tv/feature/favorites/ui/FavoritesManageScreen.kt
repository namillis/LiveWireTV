package com.livewire.tv.feature.favorites.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.livewire.tv.feature.home.wordmark
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Favourites (mockup option1-manage): a single focus column of favourite rows, each
 * with a logo tile, "★ name", the channel number/now-playing line, and per-row Move + Remove.
 * A row in Move state shows a neutral MOVING tag and up/down steppers; ▲/▼ reorder and OK (or
 * Move again) drops it. An Unavailable favourite (its channel is gone) shows a "?" tile, an
 * UNAVAILABLE tag and only Remove.
 *
 * Colour discipline (§3.3): amber is only the focus ring (via [LiveWireSurface]); the MOVING
 * and UNAVAILABLE tags are neutral/muted, never amber. Order changes go straight to the store,
 * so the Home rail and Guide filter follow. Back returns to Settings (handled by the nav host).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesManageScreen(
    viewModel: FavoritesManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    // The stream id of the row currently in Move state, or null when none is moving.
    var movingId by remember { mutableStateOf<String?>(null) }
    // Back cancels a move first; otherwise it falls through to the nav host (→ Settings).
    BackHandler(enabled = movingId != null) { movingId = null }

    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(state.rows.isNotEmpty()) {
        if (state.rows.isNotEmpty()) runCatching { firstRowFocus.requestFocus() }
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
            TopLine(summary = state.headerSummary)
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Breadcrumb()
            Spacer(Modifier.height(LiveWireDimens.SpaceM))

            if (!state.loading && state.rows.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
                    verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
                    contentPadding = PaddingValues(vertical = LiveWireDimens.SpaceS),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.rows, key = { it.entry.streamId }) { row ->
                        ManageRowView(
                            row = row,
                            moving = movingId == row.entry.streamId,
                            focusRequester = if (row == state.rows.first()) firstRowFocus else null,
                            onToggleMove = {
                                movingId = if (movingId == row.entry.streamId) null else row.entry.streamId
                            },
                            onMoveUp = { viewModel.moveUp(row.entry.streamId) },
                            onMoveDown = { viewModel.moveDown(row.entry.streamId) },
                            onRemove = {
                                if (movingId == row.entry.streamId) movingId = null
                                viewModel.remove(row.entry.streamId)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            HintLine()
        }
    }
}

@Composable
private fun TopLine(summary: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text("Favourites", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Text(
            summary,
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.padding(bottom = 2.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(rememberClockLabel(), style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted)
    }
}

@Composable
private fun Breadcrumb() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(1.5.dp)).background(LiveWireColors.OnSurfaceMuted),
        )
        Spacer(Modifier.width(LiveWireDimens.SpaceS))
        Text("SETTINGS › FAVOURITES", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageRowView(
    row: ManageRow,
    moving: Boolean,
    focusRequester: FocusRequester?,
    onToggleMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    // The row is a non-focusable container; the focusable controls are the Move and Remove
    // buttons on the right, so both are reachable with the D-pad alone. While a row is moving
    // its ring is amber (on the Move button) and the body shows a neutral MOVING tag.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(
                if (moving) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder,
                if (moving) LiveWireColors.Accent else LiveWireColors.Border,
                shape,
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_fav_move),
            contentDescription = null,
            tint = if (moving) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            row.displayNumber.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.width(16.dp),
        )
        LogoTile(name = row.entry.name, unavailable = row.unavailable)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
                if (!row.unavailable) FavoriteStar(size = 12.dp)
                Text(
                    row.entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    moving -> NeutralTag("MOVING")
                    row.unavailable -> NeutralTag("UNAVAILABLE")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                secondaryLine(row, moving),
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Controls: an available row gets Move + Remove; while moving, the Move button turns
        // into the up/down steppers (reorder) and OK on it drops. Unavailable rows get Remove
        // only. The first available row's Move button takes the screen's initial focus.
        Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
            if (!row.unavailable) {
                MoveButton(
                    moving = moving,
                    focusRequester = focusRequester,
                    onToggleMove = onToggleMove,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
            }
            ActionButton(
                iconRes = R.drawable.ic_action_delete,
                label = "Remove",
                danger = true,
                focusRequester = if (row.unavailable) focusRequester else null,
                onClick = onRemove,
            )
        }
    }
}

/**
 * The Move button. At rest OK enters Move state; while moving it shows up/down steppers, owns
 * ▲/▼ to reorder, and OK drops the item. It carries the one amber focus ring.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoveButton(
    moving: Boolean,
    focusRequester: FocusRequester?,
    onToggleMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    LiveWireSurface(
        onClick = onToggleMove,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onPreviewKeyEvent { event ->
                if (!moving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onMoveUp(); true }
                    Key.DirectionDown -> { onMoveDown(); true }
                    else -> false
                }
            },
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXs),
        ) {
            if (moving) {
                Text("▲ ▼", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = LiveWireColors.OnSurface)
                Spacer(Modifier.width(LiveWireDimens.SpaceXs))
                Text("Drop", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = LiveWireColors.OnSurface, maxLines = 1)
            } else {
                Icon(painter = painterResource(R.drawable.ic_fav_move), contentDescription = null, tint = LiveWireColors.OnSurfaceMuted, modifier = Modifier.size(14.dp))
                Text("Move", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = LiveWireColors.OnSurface, maxLines = 1)
            }
        }
    }
}

/** A focusable row-action button (Move/Remove). Remove uses the live/red tint for its glyph. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXs),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (danger) LiveWireColors.Live else LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) LiveWireColors.Live else LiveWireColors.OnSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LogoTile(name: String, unavailable: Boolean) {
    Box(
        Modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(RoundedCornerShape(LiveWireDimens.RadiusCell))
            .background(LiveWireColors.SurfaceRaised)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, RoundedCornerShape(LiveWireDimens.RadiusCell)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (unavailable) "?" else wordmark(name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The channel number/now-playing line, or the Move / Unavailable instruction. */
private fun secondaryLine(row: ManageRow, moving: Boolean): String = when {
    moving -> "Press ▲ ▼ to reposition · OK to drop"
    row.unavailable -> "Not in this provider anymore — remove it or keep for later"
    else -> row.nowPlayingTitle ?: "In your favourites"
}

@Composable
private fun NeutralTag(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(LiveWireColors.SurfaceRaised).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurfaceMuted)
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FavoriteStar(size = 48.dp)
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Text("No favourites yet", style = MaterialTheme.typography.headlineSmall, color = LiveWireColors.OnSurface)
            Spacer(Modifier.height(LiveWireDimens.SpaceS))
            Text(
                "Long-press OK on any channel to add it to Favourites",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun HintLine() {
    Text(
        "Move picks a favourite up, ▲ ▼ reorder, OK drops it. Order matches the Home rail and Guide.",
        style = MaterialTheme.typography.bodyMedium,
        color = LiveWireColors.OnSurfaceMuted,
        maxLines = 2,
    )
}

@Composable
private fun rememberClockLabel(): String = remember {
    SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(Date())
}
