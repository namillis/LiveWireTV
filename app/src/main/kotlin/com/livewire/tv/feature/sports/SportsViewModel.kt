package com.livewire.tv.feature.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.home.HomeLists
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.search.data.PendingSearch
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.data.ChannelMatcher
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SportsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val leagues: List<SportsLeague> = emptyList(),
    /** Every league's games, unsorted; the screen arranges them with [SportsList.arrange]. */
    val games: List<SportsGame> = emptyList(),
    /** The selected league chip, or null for All. */
    val filter: String? = null,
    /** Where each game plays from, by game id. Finished games have no entry. */
    val sources: Map<String, GameSource> = emptyMap(),
)

/**
 * Sports: every league's scoreboard in one list, and where each game can be watched.
 *
 * On open it asks ESPN for every league at once, and the pool for its Live Now list (a few
 * dozen channels), which is enough to show most live games as ready. Only then does the slower
 * part run, in the background: the full channel list and the guide, prepared once into a
 * [ChannelMatcher] that matches the remaining games. A game shows Finding until that is done.
 * Nothing runs on the main thread, so OK on a game never waits.
 */
@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: SportsRepository,
    private val active: com.livewire.tv.feature.providers.data.ActiveProvider,
    private val client: ProviderRepository,
    private val epg: EpgRepository,
    private val pendingSearch: PendingSearch,
) : ViewModel() {

    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var liveNow: List<LiveChannel> = emptyList()
    private var matcher: ChannelMatcher? = null
    /** True once the guide has loaded or failed: an empty match is then final. */
    private var settled = false
    private var matches: Map<String, List<ChannelMatch>> = emptyMap()
    private var resolveJob: Job? = null
    private var scoresJob: Job? = null

    /** Called each time Sports shows: refreshes the scores; channel work runs once per provider. */
    fun init() {
        if (scoresJob?.isActive != true) scoresJob = viewModelScope.launch { loadScores() }
        viewModelScope.launch {
            val prov = active.current() ?: run { settled = true; resolve(); return@launch }
            if (prov.id == provider?.id) return@launch
            provider = prov
            liveNow = emptyList(); matcher = null; settled = false; matches = emptyMap()
            launch {
                liveNow = runCatching { loadLiveNow(prov) }.getOrDefault(emptyList())
                resolve()
            }
            launch {
                val channels = runCatching { client.liveChannels(prov) }.getOrDefault(emptyList())
                matcher = withContext(Dispatchers.Default) { ChannelMatcher(channels) }
                resolve()
                val guide = loadGuide(prov, channels)
                if (guide.isNotEmpty()) matcher = withContext(Dispatchers.Default) { ChannelMatcher(channels, guide) }
                settled = true
                resolve()
            }
        }
    }

    fun setFilter(leagueId: String?) = _state.update { it.copy(filter = leagueId) }

    private suspend fun loadScores() {
        val first = _state.value.games.isEmpty()
        if (first) _state.update { it.copy(loading = true, error = null) }
        val leagues = runCatching { repository.leagues() }.getOrDefault(emptyList())
        val boards = scoreboards(leagues)
        val games = boards.filterNotNull().flatMap { it.games }
        _state.update {
            when {
                boards.isNotEmpty() && boards.all { b -> b == null } && first ->
                    it.copy(loading = false, leagues = leagues, error = "Could not load sports. Check your network and try again.")
                boards.all { b -> b == null } -> it.copy(loading = false)
                else -> it.copy(loading = false, error = null, leagues = leagues, games = games)
            }
        }
        resolve()
    }

    /** Every league's scoreboard at once; null for a league that failed. */
    private suspend fun scoreboards(leagues: List<SportsLeague>) = kotlinx.coroutines.coroutineScope {
        leagues.map { l -> async { runCatching { repository.scoreboard(l.id) }.getOrNull() } }.awaitAll()
    }

    /**
     * The pool's Live Now channels, or nothing for a provider that has no such category. Taken
     * from the full channel list when another screen has it in memory (Home loads it), else
     * fetched on their own, which is one small request.
     */
    private suspend fun loadLiveNow(prov: ProviderConfig): List<LiveChannel> {
        val pool = PoolSignals.EVENT_INFO in client.poolFeatures(prov)
        val category = client.liveCategories(prov).firstOrNull { HomeLists.isLiveNow(it.name, pool) } ?: return emptyList()
        client.cachedLiveChannels(prov)?.filter { it.categoryId == category.id }?.takeIf { it.isNotEmpty() }?.let { return it }
        return client.liveChannels(prov, categoryId = category.id)
    }

    /** Work out every unfinished game's source in the background, then publish them together. */
    private fun resolve() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val games = _state.value.games
            val pool = liveNow
            val m = matcher
            val done = settled
            val (found, sources) = withContext(Dispatchers.Default) {
                val found = HashMap<String, List<ChannelMatch>>()
                val sources = HashMap<String, GameSource>()
                for (g in games) {
                    ensureActive()
                    if (g.status.state == GameState.FINAL) continue
                    val live = SportsRepository.liveNowChannel(g, pool)
                    val list = m?.match(g)
                    if (list != null) found[g.id] = list
                    SportsList.source(g, live, list, done)?.let { sources[g.id] = it }
                }
                found to sources
            }
            matches = found
            _state.update { it.copy(sources = sources) }
        }
    }

    /** The Other channels panel for [game]: its Live Now channel first, then its matches. */
    fun options(game: SportsGame): List<SourceOption> =
        SportsList.options(SportsRepository.liveNowChannel(game, liveNow), matches[game.id].orEmpty())

    /** The channel OK plays for [game], or null when there is none (yet). */
    fun bestChannel(game: SportsGame): LiveChannel? = when (val s = _state.value.sources[game.id]) {
        is GameSource.LiveNow -> s.channel
        is GameSource.Channel -> s.match.channel
        else -> null
    }

    /** Hand Search the two teams' names; the screen then switches to Search. */
    fun searchFor(game: SportsGame) = pendingSearch.set(SportsList.searchQuery(game))

    /**
     * The guide's listings for confirming which channel airs a game (best-effort; empty on
     * failure). Same window and channel set as Search, so the two screens share one cached
     * guide and whichever opens second downloads nothing. Only a listing whose title looks
     * like a game keeps its description: the matcher reads no other description, and
     * descriptions are most of the guide's memory.
     */
    private suspend fun loadGuide(
        prov: ProviderConfig,
        loaded: List<LiveChannel>,
    ): Map<String, List<EpgProgramme>> = runCatching {
        val now = System.currentTimeMillis()
        val window = EpgWindow(
            startMs = now - TimeUnit.HOURS.toMillis(2),
            endMs = now + TimeUnit.HOURS.toMillis(6),
        )
        val ids = loaded.mapNotNullTo(HashSet()) { it.epgChannelId }
        if (ids.isEmpty()) return@runCatching emptyMap()
        val fetched = epg.fetch(prov, window, ids)
        withContext(Dispatchers.Default) {
            fetched.channels.associate { c ->
                c.id to fetched.programmesFor(c.id).map { p ->
                    if (SportsRepository.looksLikeGame(p.title)) p else p.copy(description = null)
                }
            }
        }
    }.getOrDefault(emptyMap())

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
