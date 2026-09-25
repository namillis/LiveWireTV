package com.livewire.tv.feature.epg

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import java.util.concurrent.TimeUnit

private const val PX_PER_MINUTE = 6      // 30 min = 180dp
private val ROW_HEIGHT = 84.dp
private val CHANNEL_COL = 180.dp

/**
 * Full-screen EPG guide grid. Channel column on the left; each row is a horizontally
 * scrollable lane of programme cells sized by duration. Data is already windowed by
 * GuideViewModel (lean discipline #2), so we only lay out visible programmes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "Loading the guide from your provider. Large guides can take a while.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, modifier = Modifier.padding(24.dp))
            }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No guide data for this provider.")
            }
            else -> {
                val hScroll = rememberScrollState()
                val spanMinutes = TimeUnit.MILLISECONDS.toMinutes(state.windowSpanMs).toInt()
                LazyColumn {
                    items(state.rows) { row ->
                        Row(modifier = Modifier.height(ROW_HEIGHT)) {
                            // Channel name column (fixed).
                            Box(
                                modifier = Modifier.width(CHANNEL_COL).padding(12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    row.channel.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Programme lane on a fixed timeline from the window start, so
                            // every row has the same width and the shared horizontal scroll
                            // keeps them aligned (empty rows included).
                            val onPlay = {
                                viewModel.playbackTarget(row.channel)?.let { target ->
                                    onPlayChannel(target, row.channel.name)
                                }
                                Unit
                            }
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(hScroll)
                                    .width(minutesToDp(spanMinutes)),
                            ) {
                                val cells = laneCells(row.programmes, state.windowStartMs, state.windowSpanMs)
                                if (cells.isEmpty()) {
                                    ProgrammeCell(
                                        title = "No information",
                                        widthDp = spanMinutes * PX_PER_MINUTE,
                                        onClick = onPlay,
                                    )
                                } else {
                                    cells.forEach { cell ->
                                        if (cell.gapMinutes > 0) Spacer(Modifier.width(minutesToDp(cell.gapMinutes)))
                                        ProgrammeCell(
                                            title = cell.programme.title,
                                            widthDp = cell.minutes * PX_PER_MINUTE,
                                            onClick = onPlay,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun minutesToDp(minutes: Int) = (minutes * PX_PER_MINUTE).dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgrammeCell(title: String, widthDp: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(widthDp.dp).height(ROW_HEIGHT).padding(2.dp),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
