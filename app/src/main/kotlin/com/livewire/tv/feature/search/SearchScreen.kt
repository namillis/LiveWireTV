package com.livewire.tv.feature.search

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.livewire.tv.R
import com.livewire.tv.feature.home.ChannelCard
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.search.domain.GroupedSearch
import com.livewire.tv.feature.search.domain.SearchGrouping
import com.livewire.tv.feature.search.domain.SearchResult
import com.livewire.tv.feature.search.domain.SearchSection
import com.livewire.tv.feature.sports.ChannelPicker
import com.livewire.tv.feature.sports.SportsFormat
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit

/**
 * Cross-source search over the loaded corpus (channels, EPG, sports), restyled to the
 * "grouped results" mockup (design system §4, §9; Option 1). A query field across the top,
 * a count line ("12 results · 4 channels · 5 on TV · 3 games"), then, in order and only
 * when non-empty: CHANNELS as a horizontal [ChannelCard] rail, ON TV as full-width guide
 * programme rows (now or later only, now-first), and SPORTS as full-width game rows.
 *
 * Playback (§ seed): a channel or programme plays its channel; selecting a game opens the
 * shared [ChannelPicker] (the same dialog the Sports screen uses) over the full ranked list of
 * the user's channels that carry it — OK in the picker plays, Back closes only the picker and
 * returns focus to the game row. The picker's own empty state covers the no-match case.
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
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    // The field takes initial focus (mockup empty state), and D-pad Down/Up hops between
    // the field and the first result. Left from the first column falls through to the nav
    // drawer (LiveWireNavShell); we add no Left handling so that behaviour is unchanged.
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val emptyStateFocus = remember { FocusRequester() }
    var hasResultsFocusable by remember { mutableStateOf(false) }

    // Record a search when the user leaves Search with a committed query (2+ chars). Kept in
    // rememberUpdatedState so the onDispose below reads the query as it was at teardown, not
    // the value captured when the effect was first set up.
    val latestQuery = rememberUpdatedState(query)
    DisposableEffect(Unit) {
        onDispose { viewModel.recordSearchIfEligible(latestQuery.value) }
    }

    // Whether the empty state (nothing typed) has anything focusable to receive Down.
    val emptyHasHistory = state.recentSearches.isNotEmpty() || state.recentChannels.isNotEmpty()

    // Channel picker (shared with Sports): opening a game shows it; Back closes only the
    // picker and returns focus to the SAME game row that opened it (not the field or top).
    var pickerGame by remember { mutableStateOf<SportsGame?>(null) }
    var pickerReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(Unit) { viewModel.init() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    fun playChannel(channel: LiveChannel) {
        // Opening a result also commits the current query as a recent search.
        viewModel.recordSearchIfEligible(query)
        viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name) }
    }

    fun dismissPicker() {
        val returnTo = pickerReturnFocus
        pickerGame = null
        pickerReturnFocus = null
        returnTo?.let { runCatching { it.requestFocus() } }
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
                SearchField(
                    value = query,
                    onValueChange = { query = it; viewModel.run(it) },
                    onSearch = { runCatching { resultsFocus.requestFocus() } },
                    focusRequester = fieldFocus,
                    downTarget = when {
                        state.query.isBlank() && emptyHasHistory -> emptyStateFocus
                        hasResultsFocusable -> resultsFocus
                        else -> null
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(LiveWireDimens.SpaceL))
                Text(
                    if (state.query.isBlank()) "Type with the remote keyboard" else "Results update as you type",
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }

            // Indexing indicator: a thin grey progress bar with a short label, shown ONLY
            // while the corpus is still loading (§7, §9.9). Removed once results are ready.
            if (state.loading) {
                Column(Modifier.padding(top = LiveWireDimens.SpaceS)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = LiveWireColors.ProgressFill,
                        trackColor = LiveWireColors.ProgressTrack,
                    )
                    Text(
                        "Indexing channels, guide, and games…",
                        style = LiveWireTheme.tokens.overline,
                        color = LiveWireColors.OnSurfaceMuted,
                        modifier = Modifier.padding(top = LiveWireDimens.SpaceXs),
                    )
                }
            }

            val grouped = remember(state.results) { SearchGrouping.group(state.results) }
            LaunchedEffect(grouped) { hasResultsFocusable = !grouped.isEmpty() }

            when {
                state.query.isBlank() -> EmptyState(
                    recentSearches = state.recentSearches,
                    recentChannels = state.recentChannels,
                    viewModel = viewModel,
                    firstFocus = emptyStateFocus,
                    onSelectSearch = { picked ->
                        query = picked
                        viewModel.run(picked)
                        runCatching { fieldFocus.requestFocus() }
                    },
                    onPlayChannel = ::playChannel,
                )
                grouped.isEmpty() && !state.loading -> NoResults(state.query)
                else -> Results(
                    grouped = grouped,
                    viewModel = viewModel,
                    firstResultFocus = resultsFocus,
                    onPlayChannel = ::playChannel,
                    onOpenPicker = { game, rowFocus ->
                        pickerReturnFocus = rowFocus
                        pickerGame = game
                    },
                )
            }
        }

        // The shared channel picker (same dialog as Sports). OK plays the chosen channel;
        // Back closes only the picker and returns focus to the game row that opened it.
        pickerGame?.let { game ->
            ChannelPicker(
                game = game,
                matches = viewModel.channelsForGame(game),
                onPick = { m ->
                    pickerGame = null
                    pickerReturnFocus = null
                    playChannel(m.channel)
                },
                onDismiss = ::dismissPicker,
            )
        }
    }
}

/**
 * The query field (§9.8): a filled [LiveWireColors.SurfaceRaised] box with a leading search
 * icon and an inline placeholder, showing a 2dp amber ring only while focused. Built on
 * [BasicTextField] (not Material's OutlinedTextField, which forces a floating label and its
 * own oversized metrics) so it matches the mockup's proportions and stays IME-capable.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    downTarget: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(
                width = if (focused) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder,
                color = if (focused) LiveWireColors.Accent else LiveWireColors.Border,
                shape = shape,
            )
            .padding(horizontal = LiveWireDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_search),
            contentDescription = null,
            tint = if (focused) LiveWireColors.Accent else LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(LiveWireDimens.SpaceS))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    "Search channels, guide, and sports",
                    style = MaterialTheme.typography.titleMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = LiveWireColors.OnSurface),
                cursorBrush = SolidColor(LiveWireColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    // D-pad Down leaves the field for the results; Up has nowhere above.
                    .dpadVerticalExit(down = downTarget),
            )
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
    onOpenPicker: (game: SportsGame, rowFocus: FocusRequester) -> Unit,
) {
    val now = System.currentTimeMillis()
    val firstSection = grouped.nonEmptySections().firstOrNull()
    // One FocusRequester per game row, so closing the picker (Back) can return focus to the
    // exact row that opened it. Rebuilt only when the games list changes.
    val gameRowFocus = remember(grouped.games) { grouped.games.map { FocusRequester() } }

    LazyColumn(
        // Section-to-section spacing (§ railSpacing). Rows inside a section pack tighter (§5).
        verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL),
        contentPadding = PaddingValues(top = LiveWireDimens.SpaceM, bottom = LiveWireDimens.SpaceS),
    ) {
        item(key = "count") {
            Text(
                SearchGrouping.countLine(grouped),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
            )
        }

        // CHANNELS — a horizontal card rail.
        if (grouped.channels.isNotEmpty()) {
            item(key = "channels") {
                Column {
                    SectionHeader("Channels", "· ${grouped.channels.size}")
                    ChannelRail(
                        channels = grouped.channels,
                        viewModel = viewModel,
                        now = now,
                        firstFocus = firstResultFocus.takeIf { firstSection == SearchSection.CHANNELS },
                        onPlayChannel = onPlayChannel,
                    )
                }
            }
        }

        // ON TV — full-width guide programme rows, packed tight.
        if (grouped.programmes.isNotEmpty()) {
            // One item per section, so rows pack at SpaceS instead of the section gap.
            // A section holds at most SearchIndex.PROGRAMME_LIMIT rows, so this stays cheap.
            item(key = "ontv") {
                Column(verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
                    SectionHeader("On TV", "· GUIDE")
                    grouped.programmes.forEachIndexed { index, r ->
                        val programme = r.programme ?: return@forEachIndexed
                        ProgrammeRow(
                            result = r,
                            now = now,
                            channelName = viewModel.channelNameForProgramme(programme),
                            focusRequester = firstResultFocus.takeIf {
                                index == 0 && firstSection == SearchSection.ON_TV
                            },
                            onClick = { viewModel.channelForProgramme(programme)?.let(onPlayChannel) },
                        )
                    }
                }
            }
        }

        // SPORTS — full-width game rows, packed tight.
        if (grouped.games.isNotEmpty()) {
            item(key = "sports") {
                Column(verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS)) {
                    SectionHeader("Sports", "· ${grouped.games.size} games")
                    grouped.games.forEachIndexed { index, r ->
                        val game = r.game ?: return@forEachIndexed
                        val rowFocus = gameRowFocus[index]
                        GameRow(
                            result = r,
                            rowFocus = rowFocus,
                            initialFocus = firstResultFocus.takeIf {
                                index == 0 && firstSection == SearchSection.SPORTS
                            },
                            // OK opens the shared channel picker with the full ranked list;
                            // the picker's OK plays and its empty state covers no-match.
                            onClick = { onOpenPicker(game, rowFocus) },
                        )
                    }
                }
            }
        }
    }
}

/** An overline section header with the 2×12dp muted bar before it (CHANNELS / ON TV · GUIDE / SPORTS). */
@Composable
private fun SectionHeader(header: String, suffix: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = LiveWireDimens.SpaceS),
    ) {
        Box(Modifier.width(2.dp).height(12.dp).background(LiveWireColors.OnSurfaceMuted))
        Spacer(Modifier.width(LiveWireDimens.SpaceS))
        Text(
            header.uppercase(),
            style = LiveWireTheme.tokens.overline,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
        )
        if (suffix.isNotBlank()) {
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            Text(
                suffix.uppercase(),
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
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
        contentPadding = PaddingValues(horizontal = LiveWireDimens.SpaceXs, vertical = LiveWireDimens.SpaceXs),
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
 * A full-width guide programme row (ON TV): a left pill (red NOW while airing, else the small
 * mono-muted start-time pill), the time range, the programme title, and the channel name at
 * the right. Selecting it plays the programme's channel. Wide focus scale so the ring never
 * clips. Tight vertical padding so ~3 rows sit in view (§5).
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
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            // Fixed-width pill column so the time ranges line up across rows.
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                if (airing) NowPill() else Text(
                    SearchGrouping.programmePill(programme, now),
                    style = LiveWireTheme.tokens.tag,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            Text(
                SearchGrouping.programmeTimeRange(programme),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                modifier = Modifier.width(132.dp),
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
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
    }
}

/**
 * A full-width game row (SPORTS): league code, the "Away @ Home on NETWORK" match, the
 * score (or an em dash) and the live status (red while in progress) or start time. Selecting
 * it opens the shared channel picker for the game. Wide focus scale; tight padding (§5).
 *
 * [rowFocus] is this row's own requester, so closing the picker returns focus here; when this
 * is the first result on screen it ALSO carries [initialFocus] for the field→results hop.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GameRow(
    result: SearchResult,
    rowFocus: FocusRequester,
    initialFocus: FocusRequester?,
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
        modifier = Modifier.fillMaxWidth()
            .focusRequester(rowFocus)
            .then(if (initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
        ) {
            Text(
                game.leagueId.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.width(52.dp),
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                    SearchGrouping.gameStatus(game, System.currentTimeMillis()),
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
 * The empty state (nothing typed), per the approved mockup (option1-empty): the focused query
 * field (owned by the caller) sits above; here we show, in order and each only when non-empty,
 * RECENT SEARCHES as a row of clock pill chips (most recent first, capped in the store), then
 * JUMP BACK IN as a rail of recently watched channels reusing Home's [ChannelCard] (now-playing
 * + progress + "N min left"), then the existing one-line hint. With no history at all only the
 * hint shows — matching today's behaviour aside from the always-present hint.
 *
 * D-pad: the caller routes Down-from-field to [firstFocus] (the first chip, or the first card
 * when there are no chips). Selecting a chip fills the field and runs its search; selecting a
 * card plays that channel. Amber appears only on the focused element (via [LiveWireSurface]).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    recentSearches: List<String>,
    recentChannels: List<RecentChannel>,
    viewModel: SearchViewModel,
    firstFocus: FocusRequester,
    onSelectSearch: (String) -> Unit,
    onPlayChannel: (LiveChannel) -> Unit,
) {
    val now = System.currentTimeMillis()
    val hasChips = recentSearches.isNotEmpty()
    val hasCards = recentChannels.isNotEmpty()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceXl),
        contentPadding = PaddingValues(top = LiveWireDimens.SpaceL, bottom = LiveWireDimens.SpaceS),
    ) {
        // ── RECENT SEARCHES ── a wrap of clock pill chips, most recent first.
        if (hasChips) {
            item(key = "recent-searches") {
                Column {
                    SectionHeader("Recent searches", "")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceM),
                        verticalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceS),
                        modifier = Modifier.padding(LiveWireDimens.SpaceXs),
                    ) {
                        recentSearches.forEachIndexed { index, term ->
                            RecentSearchChip(
                                term = term,
                                onClick = { onSelectSearch(term) },
                                focusRequester = firstFocus.takeIf { index == 0 },
                            )
                        }
                        // A trailing "Clear" chip removes all history (searches + channels).
                        ClearChip(onClick = { viewModel.clearHistory() })
                    }
                }
            }
        }

        // ── JUMP BACK IN ── a rail of recently watched channels (Home ChannelCard).
        if (hasCards) {
            item(key = "jump-back-in") {
                Column {
                    SectionHeader("Jump back in", "")
                    LazyRow(
                        contentPadding = PaddingValues(
                            horizontal = LiveWireDimens.SpaceXs,
                            vertical = LiveWireDimens.SpaceXs,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.RailGap),
                    ) {
                        itemsIndexed(
                            recentChannels,
                            key = { _, r -> "w:${r.watched.providerId}:${r.watched.streamId}" },
                        ) { index, recent ->
                            RecentChannelCard(
                                recent = recent,
                                nowPlaying = viewModel.nowPlayingFor(recent, now),
                                onClick = {
                                    recent.channel?.let(onPlayChannel)
                                },
                                // First card takes Down-from-field only when there are no chips.
                                focusRequester = firstFocus.takeIf { index == 0 && !hasChips },
                            )
                        }
                    }
                }
            }
        }

        // ── The one-line hint (always shown, as today). ──
        item(key = "hint") {
            Text(
                "Search finds live channels, what's on now and later in the guide, and today's games — " +
                    "all at once. Start typing a channel name like FOX, a show, or a team.",
                style = MaterialTheme.typography.bodyMedium,
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

/**
 * A resting recent-search pill (mockup .chip): a clock icon + the query, in a rounded
 * [LiveWireColors.SurfaceRaised] capsule. Focus (amber ring/scale) comes from [LiveWireSurface];
 * selecting it fills the field and runs the search.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentSearchChip(
    term: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(50),
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clock),
                contentDescription = null,
                tint = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceS))
            Text(
                term,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A trailing "Clear" pill at the end of the chips row; clears all remembered history. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ClearChip(onClick: () -> Unit) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            Modifier.padding(horizontal = LiveWireDimens.SpaceL, vertical = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Clear",
                // Same text style as the search chips so the row lines up; muted colour marks
                // it as secondary.
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A "Jump back in" card. When the watched channel is still in the playlist it renders the exact
 * Home [ChannelCard] (logo, name, now-playing, progress, "N min left") and plays on select. When
 * the stream has dropped out of the playlist we still show its stored name in a matching-width
 * card, but it is not focusable/clickable (nothing to play).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentChannelCard(
    recent: RecentChannel,
    nowPlaying: com.livewire.tv.feature.epg.domain.EpgProgramme?,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val channel = recent.channel
    if (channel != null) {
        ChannelCard(
            channel = channel,
            nowPlaying = nowPlaying,
            onClick = onClick,
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        )
    } else {
        // Unavailable: a non-focusable placeholder that keeps the rail's rhythm.
        Column(
            Modifier
                .width(148.dp)
                .clip(RoundedCornerShape(LiveWireDimens.RadiusCard))
                .background(LiveWireColors.Surface)
                .padding(9.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Unavailable",
                    style = LiveWireTheme.tokens.overline,
                    color = LiveWireColors.OnSurfaceMuted,
                )
            }
            Text(
                recent.watched.name,
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = LiveWireDimens.SpaceS),
            )
        }
    }
}

/** No results for the current query (§9.9): a plain-words line, never a bare code. */
@Composable
private fun NoResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = LiveWireDimens.SpaceL)) {
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
