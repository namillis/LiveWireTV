package com.livewire.tv.feature.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.livewire.tv.R
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.TeamSide
import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.LocalOnFocus

/**
 * Sports: one list of every league's games (design mockups `bgrid/sports/a-*`). League chips
 * filter it; sections run On now, Coming up, Finished. Each unfinished game ends with where it
 * will play from (the pool's Live Now channel, one of the user's channels, Finding, or No
 * stream found), worked out in the background by [SportsViewModel], so OK plays at once.
 * Right opens the Other channels panel. With no stream found, OK searches the user's channels.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SportsScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    onSearch: () -> Unit,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var panelGame by remember { mutableStateOf<SportsGame?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    // The key hints describe the focused game, so they show only while a game has focus.
    var listFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.init() }

    val sections = remember(state.games, state.leagues, state.filter) { SportsList.arrange(state.games, state.leagues, state.filter) }
    val chips = remember(state.games, state.leagues) { SportsList.chips(state.games, state.leagues) }
    val focusedGame = state.games.firstOrNull { it.id == focusedId }

    fun play(game: SportsGame) {
        val ch = viewModel.bestChannel(game) ?: return
        viewModel.playbackTarget(ch)?.let { onPlayChannel(it, ch.name) }
    }
    fun search(game: SportsGame) {
        viewModel.searchFor(game)
        onSearch()
    }
    fun ok(game: SportsGame) = when (state.sources[game.id]) {
        is GameSource.LiveNow, is GameSource.Channel -> play(game)
        GameSource.Finding -> panelGame = game
        GameSource.None -> search(game)
        null -> Unit
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = LiveWireColors.Canvas),
    ) {
        Column(
            Modifier.fillMaxSize().padding(
                start = LiveWireDimens.SafeHorizontal,
                end = LiveWireDimens.SafeHorizontal,
                top = LiveWireDimens.SafeVertical,
                bottom = LiveWireDimens.SafeVertical,
            ),
        ) {
            if (chips.size > 1) ChipRow(chips, state.filter, viewModel::setFilter)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { listFocused = it.hasFocus }) {
                when {
                    state.loading && state.games.isEmpty() -> Loading()
                    state.error != null && state.games.isEmpty() -> CenterMessage(state.error!!)
                    sections.isEmpty() -> CenterMessage("No games today.")
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = LiveWireDimens.SpaceXs),
                    ) {
                        for ((section, games) in sections) {
                            item(key = "h-${section.name}") { SectionHeader(section) }
                            items(games, key = { it.id }) { game ->
                                GameRow(
                                    game = game,
                                    source = state.sources[game.id],
                                    onClick = { ok(game) },
                                    onMore = { panelGame = game },
                                    onFocused = { focusedId = game.id },
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.BottomEnd) {
                focusedGame?.takeIf { listFocused }?.let { g -> hintsFor(g, state.sources[g.id])?.let { KeyHints(*it) } }
            }
        }
    }

    panelGame?.let { game ->
        val options = remember(game, state.sources) { viewModel.options(game) }
        SourcesPanel(
            game = game,
            league = state.leagues.firstOrNull { it.id == game.leagueId },
            source = state.sources[game.id],
            options = options,
            onPick = { option ->
                panelGame = null
                viewModel.playbackTarget(option.channel)?.let { onPlayChannel(it, option.channel.name) }
            },
            onSearch = { panelGame = null; search(game) },
            onDismiss = { panelGame = null },
        )
    }
}

/** The key hints for the focused row, or null for a finished game (nothing to do). */
private fun hintsFor(game: SportsGame, source: GameSource?): Array<Pair<String, String>>? {
    val more = "▶" to "Other channels"
    return when (source) {
        is GameSource.LiveNow -> arrayOf("OK" to "Watch on Live Now", more)
        is GameSource.Channel -> {
            val now = if (game.status.state == GameState.IN_PROGRESS) "" else " now"
            // Per-event channel names run long; the hint keeps their start.
            val name = source.match.channel.name.trim().let { if (it.length > 24) it.take(23).trimEnd() + "…" else it }
            arrayOf("OK" to "Watch $name$now", more)
        }
        GameSource.Finding -> arrayOf("OK" to "Show channels")
        GameSource.None -> arrayOf("OK" to "Search my channels", more)
        null -> null
    }
}

/** League chips: All first, each with its game count. The selected chip has the raised fill. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChipRow(chips: List<LeagueChip>, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().height(27.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { chip ->
            val sel = chip.id == selected
            LiveWireSurface(
                onClick = { onSelect(chip.id) },
                restingColor = if (sel) LiveWireColors.SurfaceRaised else Color.Transparent,
                restingBorder = false,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(25.dp),
            ) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chip.name,
                        style = LiveWireType.Title.copy(fontSize = 10.5.sp),
                        color = if (sel || LocalOnFocus.current) LiveWireTheme.onSurface else LiveWireTheme.onSurfaceMuted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(chip.count.toString(), style = LiveWireType.Mono.copy(fontSize = 8.sp), color = LiveWireTheme.onSurfaceMuted)
                }
            }
        }
    }
}

/** "● ON NOW", "COMING UP", "FINISHED". */
@Composable
private fun SectionHeader(section: SportsSection) {
    Row(
        Modifier.fillMaxWidth().height(20.dp).padding(start = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (section == SportsSection.ON_NOW) {
            Box(Modifier.padding(bottom = 3.dp).size(5.dp).clip(CircleShape).background(LiveWireColors.Live))
        }
        Text(section.label.uppercase(), style = LiveWireType.Overline, color = LiveWireColors.OnSurfaceMuted)
    }
}

/**
 * One game: league, the two teams with badges and scores, the status (red while live), the
 * network ESPN lists, and where OK will play from. Finished games are faded until focused.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GameRow(
    game: SportsGame,
    source: GameSource?,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val live = game.status.state == GameState.IN_PROGRESS
    val scored = SportsFormat.showScores(game)
    LiveWireSurface(
        onClick = onClick,
        restingColor = Color.Transparent,
        restingBorder = false,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (game.status.state == GameState.FINAL && !focused) 0.55f else 1f)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && source != null) { onMore(); true } else false
            },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                game.leagueId.uppercase(),
                style = LiveWireType.Overline.copy(fontSize = 7.5.sp),
                color = LiveWireTheme.onSurfaceMuted,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TeamLine(game.away, if (scored) game.away.score else null, if (scored) game.home.score else null)
                TeamLine(game.home, if (scored) game.home.score else null, if (scored) game.away.score else null)
            }
            Row(Modifier.width(118.dp).padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(LiveWireColors.Live))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    statusText(game),
                    style = LiveWireType.Mono.copy(fontSize = 8.5.sp),
                    color = if (live) LiveWireColors.Live else LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                game.broadcastNetworks.joinToString(", "),
                style = LiveWireType.Sub.copy(fontSize = 9.sp),
                color = LiveWireTheme.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(150.dp), contentAlignment = Alignment.CenterEnd) { source?.let { SourcePill(it) } }
        }
    }
}

/** The status column: the clock while live, the start time before, "Final" after. */
private fun statusText(game: SportsGame): String = when (game.status.state) {
    GameState.PRE -> SportsList.startLabel(game.startTimeMs, System.currentTimeMillis())
    else -> SportsFormat.statusLine(game)
}

/** A team: badge, code, name, and its score (bold when leading or tied, muted when behind). */
@Composable
private fun TeamLine(team: TeamSide, score: Int?, other: Int?) {
    Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(team)
        Spacer(Modifier.width(7.dp))
        Text(team.abbreviation, style = LiveWireType.Mono.copy(fontSize = 8.5.sp), color = LiveWireTheme.onSurfaceMuted, modifier = Modifier.width(30.dp), maxLines = 1)
        Text(
            team.name,
            style = LiveWireType.Title.copy(fontSize = 11.sp),
            color = LiveWireTheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null) {
            Text(
                score.toString(),
                style = LiveWireType.Heading.copy(fontSize = 12.5.sp, lineHeight = 15.sp),
                color = if (other == null || score >= other) LiveWireTheme.onSurface else LiveWireTheme.onSurfaceMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(30.dp),
                maxLines = 1,
            )
        }
    }
}

/** The team's logo in a small circle, or its code when ESPN sends no logo. */
@Composable
private fun TeamBadge(team: TeamSide) {
    Box(Modifier.size(15.dp).clip(CircleShape).background(LiveWireColors.SurfaceRaised), contentAlignment = Alignment.Center) {
        if (team.logoUrl != null) {
            AsyncImage(model = team.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(13.dp))
        } else {
            Text(team.abbreviation.take(3), style = LiveWireType.Tag.copy(fontSize = 5.sp), color = LiveWireColors.OnSurface)
        }
    }
}

/**
 * Where OK plays from. Live Now is a filled pill with a play mark; a channel is an outlined pill
 * with its name; Finding and No stream found are plain faint text. On the focused row the fills
 * invert (dark on the light row).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourcePill(source: GameSource) {
    val onFocus = LocalOnFocus.current
    val pill = RoundedCornerShape(50)
    when (source) {
        is GameSource.LiveNow -> Row(
            Modifier.height(20.dp).clip(pill).background(if (onFocus) LiveWireColors.OnFocus else LiveWireColors.SurfaceRaised).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (onFocus) LiveWireColors.Focus else LiveWireColors.OnSurface
            Icon(painterResource(R.drawable.ic_fav_play), contentDescription = null, tint = fg, modifier = Modifier.size(8.dp))
            Spacer(Modifier.width(5.dp))
            Text("Live Now", style = LiveWireType.Title.copy(fontSize = 9.sp), color = fg, maxLines = 1)
        }
        is GameSource.Channel -> Box(
            Modifier.height(20.dp).widthIn(max = 150.dp)
                .border(1.dp, if (onFocus) LiveWireColors.OnFocusMuted else LiveWireColors.BorderStrong, pill)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(source.match.channel.name, style = LiveWireType.Sub.copy(fontSize = 9.sp), color = LiveWireTheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GameSource.Finding, GameSource.None -> Text(
            SportsList.pillLabel(source) + if (source == GameSource.Finding) "…" else "",
            style = LiveWireType.Sub.copy(fontSize = 9.sp),
            color = if (onFocus) LiveWireColors.OnFocusMuted else LiveWireColors.OnSurfaceFaint,
            maxLines = 1,
            modifier = Modifier.padding(end = 9.dp),
        )
    }
}

/**
 * The Other channels panel: a sheet at the right over the dimmed list, with the game, then its
 * channels best first (the pool's Live Now channel tagged BEST). Back or Left closes it. A
 * [Dialog] keeps D-pad focus inside it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourcesPanel(
    game: SportsGame,
    league: SportsLeague?,
    source: GameSource?,
    options: List<SourceOption>,
    onPick: (SourceOption) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(options.isEmpty(), source) { runCatching { first.requestFocus() } }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(LiveWireColors.Scrim))
            Column(
                Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(LiveWireColors.Surface)
                    .drawBehind { drawLine(LiveWireColors.BorderStrong, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onDismiss(); true } else false
                    }
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("OTHER CHANNELS", style = LiveWireType.Overline, color = LiveWireColors.OnSurfaceMuted)
                Text(SportsList.title(game), style = LiveWireType.Heading, color = LiveWireColors.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        league?.name,
                        statusText(game),
                        game.broadcastNetworks.takeIf { it.isNotEmpty() }?.let { "On ${it.joinToString(", ")}" },
                    ).joinToString(" · "),
                    style = LiveWireType.Mono,
                    color = LiveWireColors.OnSurfaceMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // The channels take all the height between the heading and the key hints.
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        options.isNotEmpty() -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(options, key = { _, o -> o.channel.streamId }) { i, o ->
                                OptionRow(o, best = i == 0, onClick = { onPick(o) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                            }
                        }
                        source == GameSource.Finding -> Text("Finding channels…", style = LiveWireType.Body, color = LiveWireColors.OnSurfaceMuted)
                        else -> {
                            Text(SportsFormat.noChannelMessage(game), style = LiveWireType.Body, color = LiveWireColors.OnSurfaceMuted)
                            OptionRow(
                                SourceOption(
                                    com.livewire.tv.feature.providers.domain.LiveChannel("", "Search my channels", categoryId = ""),
                                    "Look for “${SportsList.searchQuery(game)}” in every channel and the guide",
                                    false,
                                ),
                                best = false,
                                onClick = onSearch,
                                modifier = Modifier.focusRequester(first),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    KeyHints("OK" to "Watch", "◀" to "Back to games")
                }
            }
        }
    }
}

/** A channel in the panel: its name (with a BEST tag on the first) and why it is listed. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionRow(option: SourceOption, best: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        restingBorder = false,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (option.liveNow) "Live Now · ${option.channel.name}" else option.channel.name,
                    style = LiveWireType.Title.copy(fontSize = 11.sp),
                    color = LiveWireTheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (best) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "BEST",
                        style = LiveWireType.Tag,
                        color = if (LocalOnFocus.current) LiveWireColors.Focus else LiveWireColors.OnSurfaceSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (LocalOnFocus.current) LiveWireColors.OnFocus else LiveWireColors.Canvas)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(option.reason, style = LiveWireType.Sub.copy(fontSize = 9.sp), color = LiveWireTheme.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "Loading scores from the sports service. This can take a moment.",
                style = LiveWireType.Body,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = LiveWireDimens.SpaceL),
            )
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, style = LiveWireType.Body, color = LiveWireColors.OnSurfaceMuted, modifier = Modifier.padding(LiveWireDimens.SpaceXl))
    }
}
