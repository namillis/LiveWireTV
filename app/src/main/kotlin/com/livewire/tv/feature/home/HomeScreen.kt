package com.livewire.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.favorites.ui.ChannelMenuOverlay
import com.livewire.tv.feature.favorites.ui.FavoriteToastHost
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/** Left inset for rails. The collapsed drawer rail already sits to the left of this. */
private val RailInset = LiveWireDimens.SafeHorizontal

/** The focus settle delay before the hero band recomposes, so fast D-pad scrolling stays smooth (section 9.1). */
private const val HERO_SETTLE_MS = 150L

/**
 * Home — a hero band driven by the focused channel, with the user's live channels as
 * focusable D-pad rails below it (design system sections 4, 9.1, 9.3). Selecting a channel
 * opens the player. Section navigation lives in the left drawer (LiveWireNavShell).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlayChannel: (target: PlaybackTarget, title: String, fromFavorites: Boolean) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The self-update surface, scoped to the Activity so Home and Settings share one state.
    val updateViewModel = com.livewire.tv.feature.update.rememberActivityUpdateViewModel()
    // Automatic launch check: honours the 24h gate, the auto-check switch and the player flag
    // (the player is never open while Home is composed, so pass false). Runs once per Home entry.
    LaunchedEffect(Unit) { updateViewModel.autoCheck(playerOpen = false) }
    val pendingUpdatedVersion by updateViewModel.pendingUpdatedVersion.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    // True when the update dialog is on screen and owns focus, so Home must NOT fight it for
    // focus by re-requesting a card (the dialog traps focus while shown).
    val updateDialogShowing = com.livewire.tv.feature.update.isDialogState(updateState)

    // The ViewModel lives as long as Home's back-stack entry, so coming back from the player
    // or another section keeps the loaded rails. Reloading on every return showed a spinner
    // with nothing focusable, and focus fell into the nav drawer.
    LaunchedEffect(Unit) { if (viewModel.state.value.rails.isEmpty()) viewModel.load() }

    // On a TV nothing is focused until something asks for it, so the first D-pad press
    // would be spent just landing on the screen. Focus a channel card whenever Home
    // appears: the first card on first launch, and the card the user last had focused
    // when they come back (from the player or another section). The key is saved with
    // this back-stack entry. Without this, focus falls into the nav drawer and opens it.
    val cardFocus = remember { FocusRequester() }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cardHasFocus by remember { mutableStateOf(false) }
    // The channel whose hold-OK menu is open, or null when closed.
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // The channel whose Channel info panel is open, or null when closed.
    var infoChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // When an overlay closes (not via Play), restore focus to the opener by stream id. The
    // token bumps on each close so the restore effect re-runs even for the same channel.
    var restoreStreamId by remember { mutableStateOf<String?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }
    val hasChannels = state.rails.any { it.channels.isNotEmpty() }
    val firstRailIndexForFocus = state.rails.indexOfFirst { it.channels.isNotEmpty() }
    val targetKey = lastFocusedKey ?: "$firstRailIndexForFocus:0"

    // The hero band follows focus, but only after a 150ms settle so holding a D-pad
    // direction doesn't recompose the band on every step (section 9.1). We track the
    // raw focused key immediately and debounce it into the value the band renders.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var heroKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { focusedKey }
            .distinctUntilChanged()
            .debounce(HERO_SETTLE_MS)
            .collect { heroKey = it }
    }
    // guideLoaded flips once the EPG arrives; recompute the hero content when it does.
    val heroContent = remember(heroKey, state.rails, state.guideLoaded) {
        heroKey?.let { key ->
            val (r, c) = key.split(":").let { it[0].toInt() to it[1].toInt() }
            state.rails.getOrNull(r)?.channels?.getOrNull(c)?.let { channel ->
                HeroContent(channel, viewModel.nowPlaying(channel), viewModel.upNext(channel))
            }
        }
    }

    LaunchedEffect(hasChannels, updateDialogShowing) {
        if (hasChannels && !updateDialogShowing) {
            // Coming back from the player, the player's focused node is removed in the same
            // frame Home appears; Compose then clears focus and hands it to the first
            // focusable thing, the nav drawer. So don't stop at the first success: keep the
            // card focused until it has held focus for a few checks in a row (~150ms).
            var held = 0
            repeat(30) {
                if (updateDialogShowing) return@LaunchedEffect
                if (cardHasFocus) held++ else {
                    held = 0
                    runCatching { cardFocus.requestFocus() }
                }
                if (held >= 3) return@LaunchedEffect
                delay(50)
            }
        }
    }

    // Restore focus to the opener card when an overlay (menu / Channel info) closes for any
    // reason but Play. Resolve the opener's stream id to its current position (a favourite
    // toggle may have added/removed the Favourites rail, shifting indices), point cardFocus at
    // it via lastFocusedKey, then hold focus until it sticks — otherwise focus falls to the nav
    // drawer and opens it. Reuses the same held-focus pattern as LaunchedEffect(hasChannels).
    LaunchedEffect(restoreToken) {
        if (restoreToken == 0) return@LaunchedEffect
        val railIds = state.rails.map { rail -> rail.channels.map { it.streamId } }
        val favRailIndex = state.rails.indexOfFirst { it.isFavorites }
        val target = com.livewire.tv.feature.favorites.ui.FavoritesFocus
            .restoreTarget(railIds, favRailIndex, restoreStreamId) ?: return@LaunchedEffect
        lastFocusedKey = "${target.railIndex}:${target.cardIndex}"
        var held = 0
        repeat(40) {
            if (cardHasFocus) held++ else {
                held = 0
                runCatching { cardFocus.requestFocus() }
            }
            if (held >= 3) return@LaunchedEffect
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(state.error!!, modifier = Modifier.padding(24.dp))
                }
                state.rails.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No channels found for this provider.")
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = RailInset,
                            end = LiveWireDimens.SafeHorizontal,
                            top = LiveWireDimens.SafeVertical,
                            bottom = LiveWireDimens.SafeVertical,
                        )
                        .onFocusChanged { cardHasFocus = it.hasFocus },
                ) {
                    // The hero band leads the screen and does not scroll away with the rails.
                    HeroBand(content = heroContent, now = System.currentTimeMillis())

                    LazyColumn(contentPadding = PaddingValues(top = LiveWireDimens.RailSpacing)) {
                        itemsIndexed(state.rails) { railIndex, rail ->
                            Text(
                                rail.title.uppercase(),
                                style = LiveWireTheme.tokens.overline,
                                color = LiveWireColors.OnSurfaceMuted,
                                modifier = Modifier.padding(top = LiveWireDimens.RailSpacing, bottom = 2.dp),
                            )
                            // Vertical padding leaves room for the focused card's scale and ring (section 9.3).
                            // A small horizontal contentPadding keeps the first/last card off the hard
                            // column edge so focus scale+ring aren't clipped and the rail scrolls softly
                            // (base 9f20eca had this inset on the row; my earlier rewrite dropped it).
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = LiveWireDimens.SpaceS, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.RailGap),
                            ) {
                                itemsIndexed(rail.channels) { index, channel ->
                                    val key = "$railIndex:$index"
                                    ChannelCard(
                                        channel = channel,
                                        nowPlaying = viewModel.nowPlaying(channel),
                                        isFavorite = channel.streamId in state.favoriteIds,
                                        onClick = {
                                            viewModel.playbackTarget(channel)?.let { target ->
                                                onPlayChannel(target, channel.name, rail.isFavorites)
                                            }
                                        },
                                        onLongClick = { menuChannel = channel },
                                        modifier = Modifier
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    lastFocusedKey = key
                                                    focusedKey = key
                                                }
                                            }
                                            .then(
                                                if (key == targetKey) {
                                                    Modifier.focusRequester(cardFocus)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


        // Hold-OK channel menu (mockup option2-home): dims the screen, Favourite focused.
        menuChannel?.let { channel ->
            ChannelMenuOverlay(
                channel = channel,
                isFavourite = channel.streamId in state.favoriteIds,
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onPlay = {
                    viewModel.playbackTarget(channel)?.let { target ->
                        onPlayChannel(target, channel.name, channel.streamId in state.favoriteIds)
                    }
                },
                // The Channel info panel (Option A) opens from here (mockup channel-info/optionA).
                // Close the menu as the panel takes over; no grid-focus restore (panel has focus).
                onChannelInfo = { infoChannel = channel; menuChannel = null },
                onDismiss = {
                    menuChannel = null
                    restoreStreamId = channel.streamId
                    restoreToken++
                },
            )
        }

        // Channel info panel (Option A): a right-side sheet over a strongly dimmed Home.
        infoChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelInfoPanel(
                model = viewModel.channelInfoFor(channel),
                now = System.currentTimeMillis(),
                onPlay = {
                    viewModel.playbackTarget(channel)?.let { target ->
                        onPlayChannel(target, channel.name, channel.streamId in state.favoriteIds)
                    }
                    infoChannel = null
                },
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onDismiss = {
                    infoChannel = null
                    restoreStreamId = channel.streamId
                    restoreToken++
                },
            )
        }

        FavoriteToastHost(
            token = state.confirmation.token,
            message = state.confirmation.message,
            suppressed = infoChannel != null,
        )

        // A small neutral "Updated to X" note after a successful self-update, shown once for a
        // few seconds then cleared (brief step 6). Sits over Home; does not trap focus.
        UpdatedNote(
            version = pendingUpdatedVersion,
            onDone = updateViewModel::clearPendingUpdatedVersion,
        )

        // The self-update dialog (available / progress / verify / permission / error). Renders
        // nothing until the shared state calls for it. Trapped above Home when shown.
        com.livewire.tv.feature.update.UpdateOverlay(viewModel = updateViewModel)
    }
}

/**
 * The transient "Updated to X" confirmation on Home after a self-update restarts the app. Shows
 * a neutral pill at the top for a few seconds, then calls [onDone] to clear the stored marker so
 * it appears only once. Neutral colours, no focus.
 */
@Composable
private fun UpdatedNote(version: String?, onDone: () -> Unit) {
    if (version == null) return
    LaunchedEffect(version) {
        delay(4000)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = LiveWireDimens.SafeVertical, end = LiveWireDimens.SafeHorizontal),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            com.livewire.tv.feature.update.UpdateFormat.updatedNote(version),
            style = LiveWireTheme.tokens.tag,
            color = LiveWireColors.OnSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(LiveWireColors.SurfaceRaised)
                .padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
        )
    }
}
