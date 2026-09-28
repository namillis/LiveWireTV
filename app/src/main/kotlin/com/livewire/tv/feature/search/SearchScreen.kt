package com.livewire.tv.feature.search

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.livewire.tv.feature.home.ChannelCard
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.search.domain.GroupedSearch
import com.livewire.tv.feature.search.domain.SearchGrouping
import com.livewire.tv.feature.search.domain.SearchResult
import com.livewire.tv.feature.sports.SportsFormat
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit
import com.livewire.tv.ui.theme.liveWireTextFieldColors

/**
 * Cross-source search over the loaded corpus (channels, EPG, sports), restyled to the
 * "grouped results" mockup (design system §4, §9; Option 1). A query field across the top,
 * a count line ("12 results · 4 channels · 5 on TV · 3 games"), then, in order and only
 * when non-empty: CHANNELS as a horizontal [ChannelCard] rail, ON TV as full-width guide
 * programme rows, and SPORTS as full-width game rows.
 *
 * Playback (§ seed): a channel or programme plays its channel; a game plays the best-ranked
 * of the user's channels that carry it (same fusion the Sports picker uses), falling back to
 * opening Sports when none match — the Sports picker dialog is not reused here (it is private
 * to SportsScreen), so the top-match shortcut is used instead.
 *
 * Colour discipline (§3.3): amber only on the focused element (via [LiveWireSurface]) and the
 * focused query field's ring; red only on a NOW programme pill and a live game's status. Rails
 * stay inside the right safe margin at rest and leave room for the focus ring/scale (§2, §6).
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

    // The field takes initial focus (mockup empty state), and D-pad Down/Up hops between
    // the field and the first result. Left from the first column falls through to the nav
    // drawer (LiveWireNavShell); we add no Left handling so that behaviour is unchanged.
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    var hasResultsFocusable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.init() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    fun playChannel(channel: LiveChannel) {
        viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name) }
    }

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
            // ── Query field + hint ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; viewModel.run(it) },
                    label = { Text("Search channels, guide, and sports") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.run(query)
                        keyboard?.hide()
                        runCatching { resultsFocus.requestFocus() }
                    }),
                    colors = liveWireTextFieldColors(),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(fieldFocus)
                        // D-pad Down leaves the field for the results; Up has nowhere above.
                        .dpadVerticalExit(down = if (hasResultsFocusable) resultsFocus else null),
                )
                Spacer(Modifier.width(LiveWireDimens.SpaceL))
                Text(
                    if (state.query.isBlank()) "Type with the remote keyboard" else "Results update as you type",
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }

            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = LiveWireDimens.SpaceS),
                    color = LiveWireColors.ProgressFill,
                    trackColor = LiveWireColors.ProgressTrack,
                )
            }

            val grouped = remember(state.results) { SearchGrouping.group(state.results) }
            LaunchedEffect(grouped) { hasResultsFocusable = !grouped.isEmpty() }

            when {
                state.query.isBlank() -> EmptyHint()
                grouped.isEmpty() && !state.loading -> NoResults(state.query)
                else -> Results(
                    grouped = grouped,
                    viewModel = viewModel,
                    firstResultFocus = resultsFocus,
                    onPlayChannel = ::playChannel,
                    onOpenSports = onOpenSports,
                )
            }
        }
    }
}

/** The count line + grouped sections. The first focusable result carries [firstResultFocus]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Results(
    grouped: GroupedSearch,
    viewModel: SearchViewModel,
    firstResultFocus: FocusRequester,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenSports: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val sections = grouped.nonEmptySections()

    Column(Modifier.fillMaxSize()) {
        Text(
            SearchGrouping.countLine(grouped),
            style = MaterialTheme.typography.labelMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.padding(top = LiveWireDimens.SpaceM, bottom = LiveWireDimens.SpaceL),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXl),
            // Room at top/bottom for the focused card/row scale + ring, never clipped (§6).
            contentPadding = PaddingValues(vertical = LiveWireDimens.SpaceS),
        ) {
            // CHANNELS — a horizontal card rail.
            if (grouped.channels.isNotEmpty()) {
                item(key = "channels") {
                    Section(header = "Channels", suffix = "· ${grouped.channels.size}") {
                        ChannelRail(
                            channels = grouped.channels,
                            viewModel = viewModel,
                            now = now,
                            firstFocus = firstResultFocus.takeIf { sections.first() == com.livewire.tv.feature.search.domain.SearchSection.CHANNELS },
                            onPlayChannel = onPlayChannel,
                        )
                    }
                }
            }

            // ON TV — full-width guide programme rows.
            if (grouped.programmes.isNotEmpty()) {
                item(key = "ontv") {
                    Section(header = "On TV", suffix = "· guide") {}
                }
                itemsIndexed(grouped.programmes, key = { _, r -> "p:${r.programme?.channelId}:${r.programme?.startMs}:${r.title}" }) { index, r ->
                    val programme = r.programme ?: return@itemsIndexed
                    val isFirst = index == 0 && sections.first() == com.livewire.tv.feature.search.domain.SearchSection.ON_TV
                    ProgrammeRow(
                        result = r,
                        now = now,
                        channelName = viewModel.channelNameForProgramme(programme),
                        focusRequester = firstResultFocus.takeIf { isFirst },
                        onClick = {
                            viewModel.channelForProgramme(programme)?.let(onPlayChannel)
                        },
                    )
                    Spacer(Modifier.height(LiveWireDimens.RailGap))
                }
            }

            // SPORTS — full-width game rows.
            if (grouped.games.isNotEmpty()) {
                item(key = "sports") {
                    Section(header = "Sports", suffix = "· ${grouped.games.size} games") {}
                }
                itemsIndexed(grouped.games, key = { _, r -> "g:${r.game?.id}" }) { index, r ->
                    val game = r.game ?: return@itemsIndexed
                    val isFirst = index == 0 && sections.first() == com.livewire.tv.feature.search.domain.SearchSection.SPORTS
                    GameRow(
                        result = r,
                        focusRequester = firstResultFocus.takeIf { isFirst },
                        onClick = {
                            val channel = viewModel.topChannelForGame(game)
                            if (channel != null) onPlayChannel(channel) else onOpenSports()
                        },
                    )
                    Spacer(Modifier.height(LiveWireDimens.RailGap))
                }
            }
        }
    }
}

/** An overline section header with the 2×12dp muted bar before it, then [content]. */
@Composable
private fun Section(header: String, suffix: String, content: @Composable () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = LiveWireDimens.SpaceM),
        ) {
            Box(Modifier.width(2.dp).height(12.dp).background(LiveWireColors.OnSurfaceMuted))
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            Text(
                header.uppercase(),
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            Text(
                suffix,
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
        content()
    }
}

/** The CHANNELS rail: reuses Home's [ChannelCard]. Inset so focus scale/ring is not clipped. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRail(
    channels: List<SearchResult>,
    viewModel: SearchViewModel,
    now: Long,
    firstFocus: FocusRequester?,
    onPlayChannel: (LiveChannel) -> Unit,
) {
    LazyRow(
        // Horizontal inset keeps the first/last card's 1.04 scale + ring off the column edge
        // and inside the right safe margin at rest; vertical inset leaves room top/bottom (§9.3).
        contentPadding = PaddingValues(horizontal = LiveWireDimens.SpaceS, vertical = LiveWireDimens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.RailGap),
    ) {
        itemsIndexed(channels, key = { _, r -> "c:${r.channel?.streamId}" }) { index, r ->
            val channel = r.channel ?: return@itemsIndexed
            ChannelCard(
                channel = channel,
                nowPlaying = viewModel.nowPlaying(channel, now),
                onClick = { onPlayChannel(channel) },
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/**
 * A full-width guide programme row (ON TV): a left pill (red NOW while airing, else the
 * start time), the time range, the programme title, and the channel name at the right.
 * Selecting it plays the programme's channel. Wide focus scale so the ring never clips.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgrammeRow(
    result: SearchResult,
    now: Long,
    channelName: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val programme = result.programme ?: return
    val airing = SearchGrouping.isAiring(programme, now)
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
        ) {
            // Fixed-width pill column so the time ranges line up across rows.
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
                if (airing) NowPill() else Text(
                    SearchGrouping.programmePill(programme, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            Text(
                SearchGrouping.programmeTimeRange(programme),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                modifier = Modifier.width(140.dp),
            )
            Text(
                result.title,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                channelName,
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
    }
}

/**
 * A full-width game row (SPORTS): league code, the "Away @ Home on NETWORK" match, the
 * score (or an em dash) and the live status (red while in progress) or start time. Selecting
 * it plays the best-matched channel (else opens Sports). Wide focus scale.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GameRow(
    result: SearchResult,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val game = result.game ?: return
    val live = SportsFormat.isLive(game)
    val scored = SportsFormat.showScores(game)
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCard),
        focusedScale = LiveWireDimens.FocusScaleWide,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
        ) {
            Text(
                game.leagueId.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.width(56.dp),
            )
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${game.away.name} ",
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("@", style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurfaceMuted)
                Text(
                    " ${game.home.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (game.broadcastNetworks.isNotEmpty()) {
                    Text(
                        "  on ${game.broadcastNetworks.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = LiveWireColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (scored) {
                Text(
                    "${SportsFormat.scoreLabel(game.away.score)} – ${SportsFormat.scoreLabel(game.home.score)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LiveWireColors.OnSurface,
                    maxLines = 1,
                )
            }
            if (live) {
                StatusPill(SportsFormat.statusLine(game))
            } else {
                Text(
                    SportsFormat.statusLine(game),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The red "NOW" pill on an airing programme (§7): red fill, white dot + text. */
@Composable
private fun NowPill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(LiveWireColors.Live)
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Text("NOW", style = LiveWireTheme.tokens.tag, color = Color.White, maxLines = 1)
    }
}

/** A live game's red status pill (§7): red fill, white dot + spelled-out clock. */
@Composable
private fun StatusPill(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(LiveWireColors.Live)
            .padding(horizontal = LiveWireDimens.SpaceS, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Text(text, style = LiveWireTheme.tokens.tag, color = Color.White, maxLines = 1)
    }
}

/**
 * The empty state (nothing typed). The mockup's "Recent searches" / "Jump back in" need a
 * persisted history the app does not record today, so per the seed those are left out (and
 * reported as a follow-up); the focused query field plus this one-line hint stand in.
 */
@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(top = LiveWireDimens.SpaceXl)) {
        Text(
            "Search finds live channels, what's on now and later in the guide, and today's games — " +
                "all at once. Start typing a channel name like FOX, a show, or a team.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}

/** No results for the current query (§9.9): a plain-words line, never a bare code. */
@Composable
private fun NoResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = LiveWireDimens.SpaceXl)) {
        Text(
            "No results for \"$query\".",
            style = MaterialTheme.typography.titleMedium,
            color = LiveWireColors.OnSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            "Try a channel name, a show, or a team.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}
