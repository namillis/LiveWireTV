package com.livewire.tv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme

/** Left inset for rails. The collapsed drawer rail already sits to the left of this. */
private val RailInset = 24.dp

/**
 * Home — the user's live channels as focusable D-pad rails (Phase 3). Categories become
 * rows (TvLazyRow of ChannelCards); selecting a channel opens the player. Section
 * navigation lives in the left drawer (LiveWireNavShell), not on this screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
    val hasChannels = state.rails.any { it.channels.isNotEmpty() }
    val firstRailIndexForFocus = state.rails.indexOfFirst { it.channels.isNotEmpty() }
    val targetKey = lastFocusedKey ?: "$firstRailIndexForFocus:0"
    LaunchedEffect(hasChannels) {
        if (hasChannels) {
            // Coming back from the player, the player's focused node is removed in the same
            // frame Home appears; Compose then clears focus and hands it to the first
            // focusable thing, the nav drawer. So don't stop at the first success: keep the
            // card focused until it has held focus for a few checks in a row (~150ms).
            var held = 0
            repeat(30) {
                if (cardHasFocus) held++ else {
                    held = 0
                    runCatching { cardFocus.requestFocus() }
                }
                if (held >= 3) return@LaunchedEffect
                delay(50)
            }
        }
    }

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
            else -> LazyColumn(
                modifier = Modifier.onFocusChanged { cardHasFocus = it.hasFocus },
                contentPadding = PaddingValues(vertical = LiveWireDimens.SafeVertical),
            ) {
                itemsIndexed(state.rails) { railIndex, rail ->
                    Text(
                        rail.title.uppercase(),
                        style = LiveWireTheme.tokens.overline,
                        color = LiveWireColors.OnSurfaceMuted,
                        modifier = Modifier.padding(start = RailInset, top = LiveWireDimens.RailSpacing, bottom = 2.dp),
                    )
                    // Vertical padding leaves room for the focused card's scale and ring (section 9.3).
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = RailInset, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.RailGap),
                    ) {
                        itemsIndexed(rail.channels) { index, channel ->
                            ChannelCard(
                                channel = channel,
                                nowPlaying = viewModel.nowPlaying(channel),
                                onClick = {
                                    viewModel.playbackTarget(channel)?.let { target ->
                                        onPlayChannel(target, channel.name)
                                    }
                                },
                                modifier = Modifier
                                    .onFocusChanged { if (it.isFocused) lastFocusedKey = "$railIndex:$index" }
                                    .then(
                                        if ("$railIndex:$index" == targetKey) {
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
