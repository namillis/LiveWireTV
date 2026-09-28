package com.livewire.tv.feature.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme

/**
 * Sports scoreboard + game→channel picker (design system §9). A row of league chips over a
 * scoreboard of game cards; a Games/Standings toggle at the right. Selecting a game opens the
 * channel picker of the user's own live channels that appear to carry it, ranked best-first,
 * then plays the chosen one.
 *
 * Colour discipline (§3.3): amber only on the focused element (via [LiveWireSurface]) and the
 * current-section/league marker; red only on a LIVE game's status line. Team codes and network
 * names stay neutral. No blur, no animated gradients (§10). Content sits inside the 48dp/27dp
 * safe area beside the drawer (§2); Back-from-Sports → drawer is handled by the nav shell.
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
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
            LeagueChipRow(
                leagues = state.leagues,
                selectedId = state.selectedLeagueId,
                showStandings = showStandings,
                onSelectLeague = { viewModel.selectLeague(it) },
                onToggleStandings = { showStandings = !showStandings },
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceL))

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Loading()
                    state.error != null -> CenterMessage(state.error!!)
                    showStandings -> StandingsList(viewModel)
                    else -> Scoreboard(state.scoreboard?.games ?: emptyList()) { pickerGame = it }
                }

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

/**
 * League filter chips (§9.7): text only. The selected league is [LiveWireColors.OnSurface]
 * weight 700 with a 1.5dp amber underline; unselected chips are muted. Focus adds the ring via
 * [LiveWireSurface]. The Games/Standings toggle sits at the right, so amber never marks a
 * second "selection" beside a focused picker match.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LeagueChipRow(
    leagues: List<com.livewire.tv.feature.sports.domain.SportsLeague>,
    selectedId: String?,
    showStandings: Boolean,
    onSelectLeague: (String) -> Unit,
    onToggleStandings: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
            contentPadding = PaddingValues(vertical = LiveWireDimens.SpaceXs),
        ) {
            items(leagues) { league ->
                Chip(text = league.name, selected = league.id == selectedId) { onSelectLeague(league.id) }
            }
        }
        Spacer(Modifier.width(LiveWireDimens.SpaceM))
        Chip(text = if (showStandings) "Games" else "Standings", selected = false, onClick = onToggleStandings)
    }
}

/** One text chip. Selected = bold + amber underline; focus ring/scale via [LiveWireSurface]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.Canvas, // chips are text-only, not filled pills
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
    ) {
        Column(
            Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .width(20.dp)
                    .height(1.5.dp)
                    .background(if (selected) LiveWireColors.Accent else androidx.compose.ui.graphics.Color.Transparent),
            )
        }
    }
}

/** The scoreboard: one game card per game, in a vertical list. Empty state is a plain sentence. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Scoreboard(games: List<SportsGame>, onSelect: (SportsGame) -> Unit) {
    if (games.isEmpty()) {
        CenterMessage("No games scheduled for this league right now.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        contentPadding = PaddingValues(vertical = LiveWireDimens.SpaceXs),
    ) {
        items(games) { game -> GameCard(game) { onSelect(game) } }
    }
}

/**
 * A game card (mockup: ~95dp full-width row). Each team is one baseline row —
 * `code | name … score` — so a team's score sits on the same line as its code and name
 * (defect fix), then the status line (and, for scored games, the network) below. Scores
 * render only for in-progress/final games (§ SportsFormat.showScores); a scheduled game
 * shows its start time as the status and no score. LIVE games get a red dot + red status;
 * everything else stays neutral. Padding is kept tight so ~5 games fit at 1080p. Focus via
 * [LiveWireSurface] with the wide scale so a full-width row's ring never clips.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GameCard(game: SportsGame, onClick: () -> Unit) {
    val live = SportsFormat.isLive(game)
    val scored = SportsFormat.showScores(game)
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.Surface,
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceXl, vertical = LiveWireDimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TeamLine(game.away.abbreviation, game.away.name, if (scored) game.away.score else null, scored)
            TeamLine(game.home.abbreviation, game.home.name, if (scored) game.home.score else null, scored)
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live) {
                    LiveDot()
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                }
                Text(
                    SportsFormat.statusLine(game),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (live) LiveWireColors.Live else LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (game.broadcastNetworks.isNotEmpty()) {
                    Text(
                        game.broadcastNetworks.joinToString(", "),
                        style = MaterialTheme.typography.labelMedium,
                        color = LiveWireColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A single team row: a fixed-width mono team code (neutral), the full name, then the score
 * at the far right on the SAME baseline. [reserveScore] keeps the score column's width even
 * when this game has no score, so the two team rows and the status line stay left-aligned.
 */
@Composable
private fun TeamLine(abbr: String, name: String, score: Int?, reserveScore: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (abbr.isNotEmpty()) {
            Text(
                abbr,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.width(52.dp),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceM))
        }
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = LiveWireColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null || reserveScore) {
            Spacer(Modifier.width(LiveWireDimens.SpaceL))
            Text(
                score?.let(SportsFormat::scoreLabel) ?: "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 32.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun LiveDot() {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(LiveWireColors.Live))
}

/** Standings table. Kept simple (rank / team / W / L); rows are not focusable actions. */
@Composable
private fun StandingsList(viewModel: SportsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows = state.standings?.rows ?: emptyList()
    if (rows.isEmpty()) {
        CenterMessage("Standings are unavailable for this league.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = LiveWireDimens.SpaceS)) {
                HeaderCell("#", 40.dp)
                Text("TEAM", style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
                HeaderCell("W", 48.dp)
                HeaderCell("L", 48.dp)
            }
        }
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = LiveWireDimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                Text(r.rank ?: "", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.width(40.dp))
                Text(r.teamAbbrev, style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.wins}", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface, modifier = Modifier.width(48.dp))
                Text("${r.losses}", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface, modifier = Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, style = LiveWireTheme.tokens.overline, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.width(width))
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "Loading scores from the sports service. This can take a moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = LiveWireDimens.SpaceL),
            )
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.padding(LiveWireDimens.SpaceXl))
    }
}
