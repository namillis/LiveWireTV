package com.livewire.tv.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.feature.search.domain.SearchResult
import com.livewire.tv.feature.search.domain.SearchResultKind
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.liveWireTextFieldColors
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * Cross-source search over the loaded corpus (channels, EPG, sports). Results are
 * focusable rows; channel/programme/game route to player/guide/sports respectively.
 * Kotlin port of the Flutter SearchScreen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSports: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { viewModel.init() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.run(it) },
                label = { Text("Search channels, guide, and sports") },
                singleLine = true,
                // Results update as you type; the Search key just closes the keyboard and
                // moves focus down to the results.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.run(query)
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                colors = liveWireTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            when {
                state.query.isBlank() -> Centered("Type to search.")
                state.results.isEmpty() && !state.loading -> Centered("No results for \"${state.query}\".")
                else -> LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    items(state.results) { r ->
                        ResultRow(r) {
                            when (r.kind) {
                                SearchResultKind.CHANNEL ->
                                    r.channel?.let { channel ->
                                        viewModel.playbackTarget(channel)?.let { target ->
                                            onPlayChannel(target, channel.name)
                                        }
                                    }
                                SearchResultKind.PROGRAMME -> onOpenGuide()
                                SearchResultKind.GAME -> onOpenSports()
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val tag = when (r.kind) {
                SearchResultKind.CHANNEL -> "📺"
                SearchResultKind.PROGRAMME -> "🕑"
                SearchResultKind.GAME -> "🏀"
            }
            Text("$tag  ${r.title}", style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, modifier = Modifier.padding(top = 48.dp))
    }
}
