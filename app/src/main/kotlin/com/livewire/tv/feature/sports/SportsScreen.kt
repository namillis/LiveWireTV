package com.livewire.tv.feature.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.providers.domain.PlaybackTarget

/**
 * Sports scoreboard + game→channel picker. League chips over a scoreboard of game
 * cards (Games/Standings toggle). Selecting a game opens a picker of the user's live
 * channels that appear to carry it (fused from broadcast networks) → play. Kotlin port.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SportsScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showStandings by remember { mutableStateOf(false) }
    var pickerGame by remember { mutableStateOf<SportsGame?>(null) }

    LaunchedEffect(Unit) { viewModel.init() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // League selector + Games/Standings toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(modifier = Modifier.weight(1f)) {
                    items(state.leagues) { league ->
                        val selected = league.id == state.selectedLeagueId
                        Button(
                            onClick = { viewModel.selectLeague(league.id) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(if (selected) "▸ ${league.name}" else league.name)
                        }
                    }
                }
                Button(onClick = { showStandings = !showStandings }) {
                    Text(if (showStandings) "Games" else "Standings")
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(state.error!!, Modifier.padding(24.dp))
                    }
                    showStandings -> StandingsList(viewModel)
                    else -> Scoreboard(state.scoreboard?.games ?: emptyList()) { pickerGame = it }
                }

                // Channel picker overlay
                pickerGame?.let { game ->
                    ChannelPicker(
                        game = game,
                        matches = viewModel.channelsFor(game),
                        onPick = { m ->
                            pickerGame = null
                            viewModel.playbackTarget(m.channel)?.let { target ->
                                onPlayChannel(target, m.channel.name)
                            }
                        },
                        onDismiss = { pickerGame = null },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Scoreboard(games: List<SportsGame>, onSelect: (SportsGame) -> Unit) {
    if (games.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No games scheduled.") }
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        items(games) { game ->
            Card(
                onClick = { onSelect(game) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                scale = CardDefaults.scale(focusedScale = 1.02f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TeamRow(game.away.abbreviation, game.away.name, game.away.score)
                    TeamRow(game.home.abbreviation, game.home.name, game.home.score)
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            statusLine(game),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (game.status.state == GameState.IN_PROGRESS) Color(0xFFFF6E6E)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (game.broadcastNetworks.isNotEmpty()) {
                            Text(
                                game.broadcastNetworks.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(abbr: String, name: String, score: Int?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (abbr.isNotEmpty()) "$abbr  $name" else name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(score?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusLine(game: SportsGame): String = when (game.status.state) {
    GameState.PRE -> game.status.detail ?: "Scheduled"
    GameState.IN_PROGRESS -> game.status.displayClock ?: game.status.detail ?: "Live"
    GameState.FINAL -> game.status.detail ?: "Final"
    GameState.UNKNOWN -> game.status.detail ?: ""
}

@Composable
private fun StandingsList(viewModel: SportsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows = state.standings?.rows ?: emptyList()
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Standings unavailable for this league.") }
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("#", Modifier.width(32.dp))
                Text("Team", Modifier.weight(1f))
                Text("W", Modifier.width(40.dp))
                Text("L", Modifier.width(40.dp))
            }
        }
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(r.rank ?: "", Modifier.width(32.dp))
                Text(r.teamAbbrev, Modifier.weight(1f))
                Text("${r.wins}", Modifier.width(40.dp))
                Text("${r.losses}", Modifier.width(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelPicker(
    game: SportsGame,
    matches: List<ChannelMatch>,
    onPick: (ChannelMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    // A Dialog gets its own window: Back dismisses only the picker (not the whole
    // Sports screen) and D-pad focus cannot escape to the scoreboard behind it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val initialFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { initialFocus.requestFocus() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xE6000000)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${game.away.abbreviation} @ ${game.home.abbreviation}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (game.broadcastNetworks.isEmpty()) "No broadcast network listed."
                    else "On: ${game.broadcastNetworks.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (matches.isEmpty()) {
                    Text("No matching channel in your provider. Open the guide to find it manually.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        itemsIndexed(matches) { index, m ->
                            Card(
                                onClick = { onPick(m) },
                                // Full-width rows: the default 1.1x focus scale spills off-screen.
                                scale = CardDefaults.scale(focusedScale = 1.02f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(m.channel.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("matched ${m.matchedNetwork}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .then(if (matches.isEmpty()) Modifier.focusRequester(initialFocus) else Modifier),
                ) { Text("Close") }
            }
        }
    }
}
