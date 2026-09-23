package com.livewire.tv.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Home — the user's live channels as focusable D-pad rails (Phase 3). Categories become
 * rows (TvLazyRow of ChannelCards); selecting a channel opens the player.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlayChannel: (url: String, title: String) -> Unit,
    onOpenGuide: () -> Unit = {},
    onOpenSports: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, modifier = Modifier.padding(24.dp))
            }
            state.rails.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No channels found for this provider.")
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 24.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onOpenGuide) { Text("Guide") }
                        Text("  ")
                        Button(onClick = onOpenSports) { Text("Sports") }
                        Text("  ")
                        Button(onClick = onOpenSearch) { Text("Search") }
                        Text("  ")
                        Button(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
                items(state.rails) { rail ->
                    Text(
                        rail.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp),
                    ) {
                        items(rail.channels) { channel ->
                            ChannelCard(
                                channel = channel,
                                nowPlaying = viewModel.nowPlaying(channel),
                                onClick = {
                                    val url = viewModel.streamUrl(channel)
                                    if (url != null) onPlayChannel(url, channel.name)
                                },
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
