package com.livewire.tv.feature.search

import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.Overline
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.livewire.tv.R
import com.livewire.tv.feature.home.HomeLists
import com.livewire.tv.feature.home.OnNowRow
import com.livewire.tv.feature.home.shortMinutesLeft
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
 * B · Grid mockups (B-grid-60/61). A query field across the top, a count line ("12 results ·
 * 4 channels · 5 on TV · 3 games"), then two columns: CHANNELS as On now rows and GAMES on the
 * left, ON TV programme rows (now or later only, now first) on the right.
 *
 * Playback (§ seed): a channel or programme plays its channel; selecting a game opens the
 * shared [ChannelPicker] (the same dialog the Sports screen uses) over the full ranked list of
 * the user's channels that carry it — OK in the picker plays, Back closes only the picker and
 * returns focus to the game row. The picker's own empty state covers the no-match case.
 *
 * Focus is the light fill on rows and chips, and a light ring on the query field. Red marks
 * what is live: the NOW and LIVE badges and the dots on the progress lines.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    onPlayChannel: (target: PlaybackTarget, title: String) -> Unit,
    onOpenGuide: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    // The channel whose hold-OK menu is open, or null when closed.
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // The channel whose Channel info panel is open, or null when closed.
    var infoChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // Per-result focus requesters, keyed by channel stream id, so focus returns to the exact
    // card that opened an overlay once it closes (otherwise focus falls to the nav drawer).
    val resultCardFocus = remember { mutableMapOf<String, FocusRequester>() }
    val restoreScope = rememberCoroutineScope()
    fun restoreFocusToStreamId(streamId: String?) {
        val requester = streamId?.let { resultCardFocus[it] } ?: return
        restoreScope.launch {
            repeat(40) {
                if (runCatching { requester.requestFocus() }.isSuccess) return@launch
                kotlinx.coroutines.delay(50)
            }
        }
    }
    // Seed from the ViewModel: the nav graph keeps Search's ViewModel (and its query and
    // results) alive when you switch sections, but plain remember{} state is rebuilt empty
    // on return. Starting blank left the field empty over the old query's results, and the
    // empty state (recent searches) never showed.
    var query by remember { mutableStateOf(viewModel.state.value.query) }

    // The field takes initial focus (mockup empty state), and D-pad Down/Up hops between
    // the field and the first result. Left from the first column falls through to the nav
    // drawer (LiveWireNavShell); we add no Left handling so that behaviour is unchanged.
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val emptyStateFocus = remember { FocusRequester() }
    var hasResultsFocusable by remember { mutableStateOf(false) }
    // Set on submit: move focus to the first result once the background ranking lands.
    var focusResultsWhenReady by remember { mutableStateOf(false) }

    // Record a search when the user leaves Search with a committed query (2+ chars). Kept in
    // rememberUpdatedState so the onDispose below reads the query as it was at teardown, not
    // the value captured when the effect was first set up.
    // Only a SUBMITTED query counts; a half-typed one in the field is never recorded.
    val latestQuery = rememberUpdatedState(state.query)
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
        viewModel.recordSearchIfEligible(state.query)
        viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name) }
    }

    fun dismissPicker() {
        val returnTo = pickerReturnFocus
        pickerGame = null
        pickerReturnFocus = null
        returnTo?.let { runCatching { it.requestFocus() } }
    }

    Box(Modifier.fillMaxSize()) {
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
                    top = 16.dp,
                    bottom = LiveWireDimens.SafeVertical,
                ),
        ) {
            // ── Query field (its status hint sits inside, at the right) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = query,
                    // Typing only edits the field; results change when the query is submitted.
                    // Clearing the field returns to the empty state straight away.
                    onValueChange = { query = it; if (it.isBlank()) viewModel.submit("") },
                    onSearch = {
                        focusResultsWhenReady = query.isNotBlank()
                        viewModel.submit(query)
                    },
                    focusRequester = fieldFocus,
                    downTarget = when {
                        state.query.isBlank() && emptyHasHistory -> emptyStateFocus
                        hasResultsFocusable -> resultsFocus
                        else -> null
                    },
                    status = when {
                        state.searching -> "Searching…"
                        query.isNotBlank() && query.trim() != state.query -> "Press Search on the keyboard"
                        else -> "Remote keyboard"
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Indexing indicator: a thin grey progress bar with a short label, shown ONLY
            // while the corpus is still loading (§7, §9.9). Removed once results are ready.
            if (state.loading || state.enriching) {
                Column(Modifier.padding(top = LiveWireDimens.SpaceS)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = LiveWireColors.ProgressFill,
                        trackColor = LiveWireColors.ProgressTrack,
                    )
                    Overline("Indexing channels, guide, and games…", brandRule = false, modifier = Modifier.padding(top = LiveWireDimens.SpaceXs),
                    )
                }
            }

            val grouped = remember(state.results) { SearchGrouping.group(state.results) }
            LaunchedEffect(grouped) { hasResultsFocusable = !grouped.isEmpty() }
            LaunchedEffect(grouped, state.searching) {
                if (focusResultsWhenReady && !state.searching) {
                    focusResultsWhenReady = false
                    if (!grouped.isEmpty()) runCatching { resultsFocus.requestFocus() }
                }
            }

            when {
                state.query.isBlank() -> EmptyState(
                    recentSearches = state.recentSearches,
                    recentChannels = state.recentChannels,
                    viewModel = viewModel,
                    firstFocus = emptyStateFocus,
                    onSelectSearch = { picked ->
                        query = picked
                        viewModel.submit(picked)
                        runCatching { fieldFocus.requestFocus() }
                    },
                    onPlayChannel = ::playChannel,
                )
                grouped.isEmpty() && !state.loading && !state.enriching && !state.searching ->
                    NoResults(state.query)
                else -> Results(
                    grouped = grouped,
                    viewModel = viewModel,
                    firstResultFocus = resultsFocus,
                    onPlayChannel = ::playChannel,
                    onOpenPicker = { game, rowFocus ->
                        pickerReturnFocus = rowFocus
                        pickerGame = game
                    },
                    favoriteIds = favoriteIds,
                    onLongPress = { menuChannel = it },
                    onRegisterFocus = { id, fr -> resultCardFocus[id] = fr },
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

        // Hold-OK channel menu on a channel result (mockup option2): dims the screen.
        menuChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelMenuOverlay(
                channel = channel,
                isFavourite = channel.streamId in favoriteIds,
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onPlay = { playChannel(channel) },
                // The Channel info panel (Option A) opens from here. Close the menu as the panel
                // takes over; no card-focus restore (the panel owns focus).
                onChannelInfo = { infoChannel = channel; menuChannel = null },
                onDismiss = { menuChannel = null; restoreFocusToStreamId(channel.streamId) },
            )
        }

        // Channel info panel (Option A) over a strongly dimmed Search.
        infoChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelInfoPanel(
                model = viewModel.channelInfoFor(channel),
                now = System.currentTimeMillis(),
                onPlay = { playChannel(channel); infoChannel = null },
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onDismiss = { infoChannel = null; restoreFocusToStreamId(channel.streamId) },
            )
        }

        com.livewire.tv.feature.favorites.ui.FavoriteToastHost(
            token = confirmation.token,
            message = confirmation.message,
            suppressed = infoChannel != null,
        )
    }
}

/**
 * The query field: a pill on the surface colour with a leading search icon, an inline
 * placeholder and a status hint at the right. Focus is a 2dp light ring rather than the fill,
 * so typed text stays readable (the one place focus is a ring). Built on [BasicTextField], not
 * Material's OutlinedTextField, which forces a floating label and its own metrics.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    downTarget: FocusRequester?,
    status: String,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(
                width = if (focused) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder,
                color = if (focused) LiveWireColors.Focus else LiveWireColors.Border,
                shape = shape,
            )
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_search),
            contentDescription = null,
            tint = LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    "Search channels, the guide and games",
                    style = LiveWireType.TitleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LiveWireType.TitleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = LiveWireColors.OnSurface),
                cursorBrush = SolidColor(LiveWireColors.OnSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    // D-pad Down leaves the field for the results; Up goes to the top bar.
                    .dpadVerticalExit(down = downTarget),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(status, style = LiveWireType.Hint, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
    }
}

/**
 * The results (mockup B-grid-60): the count line, then two columns. Left: CHANNELS as On now
 * rows (the same row as Home), then GAMES. Right: ON TV programme rows, now first. The first
 * focusable result, in that order, carries [firstResultFocus] for the field-to-results hop.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Results(
    grouped: GroupedSearch,
    viewModel: SearchViewModel,
    firstResultFocus: FocusRequester,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenPicker: (game: SportsGame, rowFocus: FocusRequester) -> Unit,
    favoriteIds: Set<String>,
    onLongPress: (LiveChannel) -> Unit,
    onRegisterFocus: (String, FocusRequester) -> Unit,
) {
    val now = System.currentTimeMillis()
    val first = when {
        grouped.channels.isNotEmpty() -> SearchSection.CHANNELS
        grouped.games.isNotEmpty() -> SearchSection.SPORTS
        else -> SearchSection.ON_TV
    }
    // One FocusRequester per game row, so closing the picker (Back) can return focus to the
    // exact row that opened it. Rebuilt only when the games list changes.
    val gameRowFocus = remember(grouped.games) { grouped.games.map { FocusRequester() } }

    Column(Modifier.fillMaxSize()) {
        Text(
            SearchGrouping.countLine(grouped),
            style = LiveWireType.Mono,
            color = LiveWireTheme.onSurfaceMuted,
            modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 10.dp),
        )
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (grouped.channels.isNotEmpty()) {
                    item(key = "h:channels") { GroupHeader("Channels", grouped.channels.size) }
                    itemsIndexed(grouped.channels, key = { _, r -> "c:${r.channel?.streamId}" }) { index, r ->
                        val channel = r.channel ?: return@itemsIndexed
                        val rowFocus = remember(channel.streamId) { FocusRequester() }
                        DisposableEffect(channel.streamId) {
                            onRegisterFocus(channel.streamId, rowFocus)
                            onDispose { }
                        }
                        OnNowRow(
                            number = HomeLists.rowNumber(channel.number, index),
                            channel = channel,
                            nowPlaying = viewModel.nowPlaying(channel, now),
                            now = now,
                            showStar = channel.streamId in favoriteIds,
                            onClick = { onPlayChannel(channel) },
                            onLongClick = { onLongPress(channel) },
                            modifier = Modifier
                                .focusRequester(rowFocus)
                                .then(if (index == 0 && first == SearchSection.CHANNELS) Modifier.focusRequester(firstResultFocus) else Modifier),
                        )
                    }
                }
                if (grouped.games.isNotEmpty()) {
                    item(key = "h:games") {
                        GroupHeader("Games", grouped.games.size, top = if (grouped.channels.isNotEmpty()) 14.dp else 0.dp)
                    }
                    itemsIndexed(grouped.games, key = { i, r -> "g:${r.game?.id ?: i}" }) { index, r ->
                        val game = r.game ?: return@itemsIndexed
                        val rowFocus = gameRowFocus[index]
                        GameRow(
                            game = game,
                            now = now,
                            rowFocus = rowFocus,
                            initialFocus = firstResultFocus.takeIf { index == 0 && first == SearchSection.SPORTS },
                            // OK opens the shared channel picker with the full ranked list.
                            onClick = { onOpenPicker(game, rowFocus) },
                        )
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (grouped.programmes.isNotEmpty()) {
                    item(key = "h:ontv") { GroupHeader("On TV", grouped.programmes.size) }
                    itemsIndexed(grouped.programmes, key = { i, r -> "p:$i:${r.programme?.startMs}" }) { index, r ->
                        val programme = r.programme ?: return@itemsIndexed
                        ProgrammeRow(
                            programme = programme,
                            title = r.title,
                            now = now,
                            channelName = viewModel.channelNameForProgramme(programme),
                            focusRequester = firstResultFocus.takeIf { index == 0 && first == SearchSection.ON_TV },
                            onClick = { viewModel.channelForProgramme(programme)?.let(onPlayChannel) },
                        )
                    }
                }
            }
        }
        KeyHints("OK" to "Watch", "HOLD OK" to "Channel menu", "▲" to "Edit search", modifier = Modifier.padding(top = 8.dp))
    }
}

/** A group header: the overline with its red rule on the left, the count on the right. */
@Composable
private fun GroupHeader(title: String, count: Int?, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = top, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(title)
        Spacer(Modifier.weight(1f))
        if (count != null) Text(count.toString(), style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted)
    }
}



/**
 * An ON TV row: the LIVE badge with NOW while airing, else when it starts ("11:00 PM", or
 * "TUE 8:00 PM" on another day), then the title over the channel name and, while airing, the
 * minutes left. Selecting it plays the programme's channel.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgrammeRow(
    programme: com.livewire.tv.feature.epg.domain.EpgProgramme,
    title: String,
    now: Long,
    channelName: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val airing = SearchGrouping.isAiring(programme, now)
    ResultRow(onClick = onClick, modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier) {
        Box(Modifier.width(WHEN_WIDTH), contentAlignment = Alignment.CenterStart) {
            if (airing) NowPill() else Text(SearchGrouping.programmeWhen(programme, now), style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
        }
        val left = if (airing && !programme.endIsEstimate) shortMinutesLeft(programme, now).let { " · $it" } else ""
        ResultText(title, channelName + left)
    }
}

/**
 * A GAMES row: the LIVE badge while in progress, else the start ("SAT 9:00 AM") or the final
 * status, then "Away @ Home" over the league, score and network. Selecting it opens the shared
 * channel picker. [rowFocus] is this row's own requester, so closing the picker returns here;
 * the first result on screen ALSO carries [initialFocus] for the field-to-results hop.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GameRow(
    game: SportsGame,
    now: Long,
    rowFocus: FocusRequester,
    initialFocus: FocusRequester?,
    onClick: () -> Unit,
) {
    val live = SportsFormat.isLive(game)
    val scored = SportsFormat.showScores(game)
    ResultRow(
        onClick = onClick,
        modifier = Modifier.focusRequester(rowFocus).then(if (initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier),
    ) {
        Box(Modifier.width(WHEN_WIDTH), contentAlignment = Alignment.CenterStart) {
            if (live) StatusPill("LIVE") else Text(SearchGrouping.gameWhen(game, now), style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
        }
        val sub = listOfNotNull(
            if (live) SportsFormat.statusLine(game) else game.leagueId.uppercase(),
            if (scored) "${SportsFormat.scoreLabel(game.away.score)}–${SportsFormat.scoreLabel(game.home.score)}" else null,
            game.broadcastNetworks.takeIf { it.isNotEmpty() }?.let { "on ${it.joinToString(", ")}" },
        ).joinToString(" · ")
        ResultText("${game.away.name} @ ${game.home.name}", sub)
    }
}

/** A flat result row: invisible at rest, the light fill when focused. */
@Composable
private fun ResultRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    LiveWireSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(43.dp),
        shape = RoundedCornerShape(9.dp),
        restingColor = Color.Transparent,
        restingBorder = false,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ResultText(title: String, sub: String) {
    Column(Modifier.weight(1f)) {
        Text(title, style = LiveWireType.Title, color = LiveWireTheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub, style = LiveWireType.Sub, color = LiveWireTheme.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The left "when" column of the ON TV and GAMES rows, wide enough for "TUE 12:00 PM". */
private val WHEN_WIDTH = 72.dp

/** The LIVE badge on an airing programme: the pulse line into the red dot, then NOW. */
@Composable
private fun NowPill() {
    com.livewire.tv.ui.theme.LiveBadge(label = "NOW")
}

/** A live game's status: the LIVE badge with the spelled-out clock. */
@Composable
private fun StatusPill(text: String) {
    com.livewire.tv.ui.theme.LiveBadge(label = text.uppercase())
}

/**
 * The empty state (mockup B-grid-61): RECENT SEARCHES as clock chips with a Clear chip, then
 * JUMP BACK IN as On now rows on the left with a short explanation on the right. Each part
 * shows only when non-empty; with no history only the explanation shows.
 *
 * D-pad: the caller routes Down-from-field to [firstFocus] (the first chip, or the first row
 * when there are no chips). A chip fills the field and runs its search; a row plays.
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
    val playable = recentChannels.filter { it.channel != null }
    Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
        if (hasChips) {
            Overline("Recent searches", modifier = Modifier.padding(bottom = 9.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                recentSearches.forEachIndexed { index, term ->
                    RecentSearchChip(term = term, onClick = { onSelectSearch(term) }, focusRequester = firstFocus.takeIf { index == 0 })
                }
                ClearChip(onClick = { viewModel.clearHistory() })
            }
            Spacer(Modifier.height(22.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (playable.isNotEmpty()) {
                    item(key = "h:jump") { GroupHeader("Jump back in", playable.size) }
                    itemsIndexed(playable, key = { _, r -> "w:${r.watched.providerId}:${r.watched.streamId}" }) { index, recent ->
                        val channel = recent.channel ?: return@itemsIndexed
                        OnNowRow(
                            number = HomeLists.rowNumber(channel.number, index),
                            channel = channel,
                            nowPlaying = viewModel.nowPlayingFor(recent, now),
                            now = now,
                            showStar = false,
                            onClick = { onPlayChannel(channel) },
                            onLongClick = { },
                            modifier = if (index == 0 && !hasChips) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
            Text(
                "Search finds live channels, what is on now and later in the guide, and today's games, all at once. " +
                    "Type a channel name, a show or a team, then press Search on the keyboard.",
                style = LiveWireType.Body,
                color = LiveWireTheme.onSurfaceMuted,
                modifier = Modifier.weight(1f).padding(top = 10.dp),
            )
        }
        KeyHints("OK" to "Open keyboard", "▼" to "Recents", "▲" to "Tabs", modifier = Modifier.padding(top = 8.dp))
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
        restingColor = LiveWireColors.Surface,
        shape = RoundedCornerShape(50),
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).height(28.dp),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clock),
                contentDescription = null,
                tint = LiveWireTheme.onSurfaceMuted,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                term,
                style = LiveWireType.Title.copy(fontSize = 10.5.sp),
                color = LiveWireTheme.onSurface,
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
        restingColor = Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(28.dp),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Clear",
                style = LiveWireType.Title.copy(fontSize = 10.5.sp),
                color = LiveWireTheme.onSurface,
                maxLines = 1,
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
            style = LiveWireType.Title,
            color = LiveWireTheme.onSurface,
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            "Try a channel name, a show, or a team.",
            style = LiveWireType.Body,
            color = LiveWireTheme.onSurfaceMuted,
        )
    }
}
