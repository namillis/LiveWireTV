package com.livewire.tv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.search.domain.SearchIndex
import com.livewire.tv.feature.search.domain.SearchResult
import com.livewire.tv.feature.search.data.RecentHistoryLogic
import com.livewire.tv.feature.search.data.RecentHistoryStore
import com.livewire.tv.feature.search.data.WatchedChannel
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.SportsGame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A recently watched channel, resolved for the "Jump back in" rail: the stored reference
 *  paired with the live channel (when still in the playlist) so the card can show logo,
 *  name, and now-playing. [channel] is null when the stream is no longer available. */
data class RecentChannel(
    val watched: WatchedChannel,
    val channel: LiveChannel?,
)

data class SearchUiState(
    val loading: Boolean = true,
    // True while the guide or games are still arriving after channels are live. The
    // indexing bar stays up and "no results" waits, so a programme query is not
    // answered before its programmes exist.
    val enriching: Boolean = false,
    // The SUBMITTED query: results always belong to it. Typing alone does not change it.
    val query: String = "",
    // True while a submitted query is being ranked on a background thread.
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val recentChannels: List<RecentChannel> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val storage: ProviderStorage,
    private val client: ProviderRepository,
    private val epg: EpgRepository,
    private val sports: SportsRepository,
    private val historyStore: RecentHistoryStore,
    private val favorites: com.livewire.tv.feature.favorites.data.FavoritesStore,
    private val settingsStore: com.livewire.tv.feature.settings.data.SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Favourited stream ids for the active provider, for the result star + hold-OK menu. */
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    /** Add/remove confirmation for the top-right toast; token increases on each toggle. */
    private val _confirmation = MutableStateFlow(com.livewire.tv.feature.home.FavoriteConfirmation())
    val confirmation: StateFlow<com.livewire.tv.feature.home.FavoriteConfirmation> = _confirmation.asStateFlow()
    private var confirmToken = 0
    private var favoritesJob: Job? = null
    private var streamFormat: com.livewire.tv.feature.settings.data.StreamFormat =
        com.livewire.tv.feature.settings.data.StreamFormat.TS

    private var provider: ProviderConfig? = null
    private var index = SearchIndex()

    // Kept for result → playback / label resolution the screen asks for after ranking.
    private var channels: List<LiveChannel> = emptyList()
    // A channel's EPG id → its programmes, kept so the index can be rebuilt progressively as
    // each source arrives and so "what's on now" is an O(1) lookup per card/row. This is the
    // single retained copy of the guide's programmes: the flat list the ranking index needs is
    // derived from these values when the index is rebuilt, not held as a second field.
    private var programmesByEpgId: Map<String, List<EpgProgramme>> = emptyMap()
    private var loadedGames: List<SportsGame> = emptyList()
    // A programme's channelId → the live channel that carries it, for programme playback.
    private var channelByEpgId: Map<String, LiveChannel> = emptyMap()
    // streamId → live channel, so a stored watched entry re-binds to its playlist channel.
    private var channelByStreamId: Map<String, LiveChannel> = emptyMap()

    // Load guard: the corpus is loaded once per provider. [loadedFor] is the provider config
    // (id, endpoint, login, guide URL) the finished corpus belongs to; [loadJob] tracks an
    // in-flight load so a re-entry neither restarts nor double-loads. Switching or editing
    // the provider makes the config differ, so Search reloads.
    private var loadedFor: ProviderConfig? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    // The persisted empty-state history. Collected once; the watched list is re-joined
    // against the channel list whenever either changes so cards get logo + now-playing.
    private var rawWatched: List<WatchedChannel> = emptyList()

    init {
        historyStore.history
            .onEach { history ->
                rawWatched = history.watched
                _state.update {
                    it.copy(
                        recentSearches = history.searches,
                        recentChannels = resolveRecentChannels(history.watched),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun init() {
        // The ViewModel survives section switches, so once the corpus is loaded a
        // re-entry into Search must not re-download+re-parse the ~64 MB guide. A load
        // already in flight is left to finish; a completed load is reused as-is.
        val configuredProvider = storage.load().firstOrNull()
        if (loadJob?.isActive == true) return
        if (configuredProvider != null && configuredProvider == loadedFor) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            provider = configuredProvider
            configuredProvider?.let { observeFavorites(it.id) }
            streamFormat = runCatching { settingsStore.settings.first().streamFormat }
                .getOrDefault(com.livewire.tv.feature.settings.data.StreamFormat.TS)

            // 1) Channels first (~0.7s / 2.5 MB). Publishing them immediately makes the
            //    "Jump back in" rail and channel-name search usable right away, instead of
            //    waiting on the guide. Progressive display mirrors the Guide (PR #13).
            val loadedChannels = configuredProvider?.let {
                runCatching { client.liveChannels(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            publishChannels(loadedChannels)
            // Channels are enough to leave the loading state: the empty-state rail and
            // channel results are live now; programmes and games fill in progressively.
            _state.update {
                it.copy(
                    loading = false,
                    enriching = configuredProvider != null,
                    recentChannels = resolveRecentChannels(rawWatched),
                )
            }
            rerank()

            // 2) Guide and sports are independent, so run them concurrently and fold each
            //    into the index as it arrives (rebuilding against whatever is loaded so far).
            val epgJob = launch {
                if (configuredProvider == null) return@launch
                val programmes = runCatching {
                    val now = System.currentTimeMillis()
                    val window = EpgWindow(
                        startMs = now - TimeUnit.HOURS.toMillis(2),
                        endMs = now + TimeUnit.HOURS.toMillis(6),
                    )
                    // Only programmes on channels the user can actually open are useful results.
                    val ids = loadedChannels.mapNotNullTo(HashSet()) { it.epgChannelId }
                    val guide = epg.fetch(configuredProvider, window, ids)
                    buildList { guide.channels.forEach { addAll(guide.programmesFor(it.id)) } }
                }.getOrDefault(emptyList())
                onProgrammesLoaded(programmes)
            }
            val sportsJob = launch {
                val games = runCatching {
                    buildList { for (league in sports.leagues().take(3)) addAll(sports.scoreboard(league.id).games) }
                }.getOrDefault(emptyList())
                onGamesLoaded(games)
            }
            epgJob.join()
            sportsJob.join()
            _state.update { it.copy(enriching = false) }
            // Only a load that produced channels counts as done; an empty or failed one is
            // retried on the next visit to Search.
            if (loadedChannels.isNotEmpty()) loadedFor = configuredProvider
        }
    }

    /** Publish the channel corpus: bind lookups, seed the index (channels only), so channel
     *  results and the "Jump back in" rail are live before the guide/sports arrive. */
    private fun publishChannels(loadedChannels: List<LiveChannel>) {
        channels = loadedChannels
        // First channel wins a duplicate EPG id, matching the ranked order the index uses.
        channelByEpgId = loadedChannels
            .filter { !it.epgChannelId.isNullOrBlank() }
            .associateByTo(HashMap()) { it.epgChannelId!! }
        // First channel wins a duplicate stream id (they should be unique per provider).
        channelByStreamId = loadedChannels.associateBy { it.streamId }
        rebuildIndex()
    }

    /** Fold loaded programmes into the corpus and re-rank the current query. */
    private fun onProgrammesLoaded(programmes: List<EpgProgramme>) {
        programmesByEpgId = programmes.groupBy { it.channelId }
        rebuildIndex()
        // Recent-channel cards can now show now-playing; re-bind them too.
        _state.update { it.copy(recentChannels = resolveRecentChannels(rawWatched)) }
        rerank()
    }

    /** Fold loaded games into the corpus and re-rank the current query. */
    private fun onGamesLoaded(games: List<SportsGame>) {
        loadedGames = games
        rebuildIndex()
        rerank()
    }

    /** Rebuild the ranking index from whatever parts of the corpus are loaded so far. The flat
     *  programme list is derived here from [programmesByEpgId] rather than retained separately,
     *  so the guide's programmes are held once. */
    private fun rebuildIndex() {
        val programmes = if (programmesByEpgId.isEmpty()) emptyList()
            else programmesByEpgId.values.flatten()
        index = SearchIndex(channels = channels, programmes = programmes, games = loadedGames)
    }

    /**
     * Search for [query]. Called when the user submits (the keyboard's Search key, or a
     * recent-search chip), never per keystroke: ranking ~8,600 channels and ~100,000
     * programmes takes long enough that doing it on every letter froze the field. The
     * ranking runs on a background thread, and a newer submit cancels an older one.
     * A blank query clears the results at once so the empty state shows.
     */
    fun submit(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            _state.update { it.copy(query = "", results = emptyList(), searching = false) }
            return
        }
        _state.update { it.copy(query = q, searching = true) }
        val snapshot = index
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) { snapshot.search(q) }
            _state.update { it.copy(results = results, searching = false) }
        }
    }

    /** Re-rank the submitted query against a corpus that just grew (guide or games arrived). */
    private fun rerank() {
        val q = _state.value.query
        if (q.isNotEmpty()) submit(q)
    }

    /** Keep [favoriteIds] in step with the store for the active provider (result star + menu). */
    private fun observeFavorites(providerId: String) {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            favorites.favoriteIds(providerId).collect { ids -> _favoriteIds.value = ids }
        }
    }

    /** Toggle [channel] in the active provider's favourites (hold-OK menu) and raise a toast. */
    fun toggleFavorite(channel: LiveChannel) {
        val cfg = provider ?: return
        viewModelScope.launch {
            val wasFavourite = favorites.isFavorite(cfg.id, channel.streamId)
            favorites.toggle(cfg.id, channel)
            confirmToken += 1
            _confirmation.value = com.livewire.tv.feature.home.FavoriteConfirmation(
                token = confirmToken,
                message = com.livewire.tv.feature.favorites.ui.FavoritesUi.confirmationLabel(nowFavourite = !wasFavourite),
            )
        }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }

    /** Assemble the Channel info panel model for [channel] from the loaded corpus. */
    fun channelInfoFor(channel: LiveChannel): com.livewire.tv.feature.favorites.ui.ChannelInfoModel {
        val now = System.currentTimeMillis()
        val programmes = channel.epgChannelId?.let { programmesByEpgId[it] }.orEmpty().sortedBy { it.startMs }
        return com.livewire.tv.feature.favorites.ui.ChannelInfoModel(
            channel = channel,
            providerName = provider?.name.orEmpty(),
            categoryLabel = null,
            formatLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.formatLabel(streamFormat),
            qualityLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.qualityLabel(channel.name),
            nowPlaying = programmes.firstOrNull { it.airsAt(now) },
            upNext = programmes.filter { it.startMs > now },
            isFavourite = channel.streamId in _favoriteIds.value,
        )
    }
    /** What's on now on [channel] (for the CHANNELS rail card's "N min left"), or null. */
    fun nowPlaying(channel: LiveChannel, now: Long = System.currentTimeMillis()): EpgProgramme? {
        val id = channel.epgChannelId ?: return null
        return programmesByEpgId[id]?.firstOrNull { it.airsAt(now) }
    }

    /** The live channel a guide programme airs on, resolved by EPG id, or null. */
    fun channelForProgramme(programme: EpgProgramme): LiveChannel? = channelByEpgId[programme.channelId]

    /**
     * The channel name to show at the right of a programme row. Falls back to the EPG
     * channel id when the programme's channel is not in the current channel list.
     */
    fun channelNameForProgramme(programme: EpgProgramme): String =
        channelForProgramme(programme)?.name ?: programme.channelId

    /**
     * The user's own live channels that appear to carry [game], best-ranked first (same
     * fusion the Sports picker uses). Exposes the FULL ranked list — not just the top —
     * so Search can open the shared channel picker with every candidate. Empty when none
     * match, which the picker renders as its no-channel empty state.
     */
    fun channelsForGame(game: SportsGame): List<ChannelMatch> =
        SportsRepository.matchChannels(game, channels, programmesByEpgId)

    /**
     * Record [query] as a recent search when it clears the minimum length (2+ chars). Called
     * when the user commits a search — leaving Search or opening a result — not per keystroke,
     * so a half-typed query is never stored. The store applies dedupe/cap; the flow updates
     * [state] on write.
     */
    fun recordSearchIfEligible(query: String) {
        if (query.trim().length < RecentHistoryLogic.MIN_QUERY_LENGTH) return
        viewModelScope.launch { historyStore.recordSearch(query) }
    }

    /** Clear all remembered searches and watched channels (empty-state "Clear"). */
    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }

    /** Now-playing programme for a [RecentChannel]'s card, or null when unknown. */
    fun nowPlayingFor(recent: RecentChannel, now: Long = System.currentTimeMillis()): EpgProgramme? =
        recent.channel?.let { nowPlaying(it, now) }

    /** Playback target for a recently watched channel that is still in the playlist, or null. */
    fun playbackTargetFor(recent: RecentChannel): PlaybackTarget? =
        recent.channel?.let { playbackTarget(it) }

    /**
     * Pair each stored [WatchedChannel] with its live channel (by stream id) when the stream
     * is still in the playlist, so the "Jump back in" card can show logo + now-playing. Order
     * (most recent first) is preserved; entries whose stream has vanished keep a null channel
     * and render from the stored name alone.
     */
    private fun resolveRecentChannels(watched: List<WatchedChannel>): List<RecentChannel> =
        watched.map { RecentChannel(watched = it, channel = channelByStreamId[it.streamId]) }
}
