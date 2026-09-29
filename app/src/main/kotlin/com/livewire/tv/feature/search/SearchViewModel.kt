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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val query: String = "",
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
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var index = SearchIndex()

    // Kept for result → playback / label resolution the screen asks for after ranking.
    private var channels: List<LiveChannel> = emptyList()
    // A channel's EPG id → its programmes, so "what's on now" is an O(1) lookup per card/row.
    private var programmesByEpgId: Map<String, List<EpgProgramme>> = emptyMap()
    // A programme's channelId → the live channel that carries it, for programme playback.
    private var channelByEpgId: Map<String, LiveChannel> = emptyMap()
    // streamId → live channel, so a stored watched entry re-binds to its playlist channel.
    private var channelByStreamId: Map<String, LiveChannel> = emptyMap()

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
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val loadedChannels = mutableListOf<LiveChannel>()
            val programmes = mutableListOf<EpgProgramme>()
            val games = mutableListOf<SportsGame>()

            storage.load().firstOrNull()?.let { configuredProvider ->
                provider = configuredProvider
                runCatching {
                    // One unfiltered request returns every live channel (about 8.6k / 2.5 MB on
                    // a large panel). Searching only a few categories silently misses most
                    // channels.
                    loadedChannels.addAll(client.liveChannels(configuredProvider))
                }
                runCatching {
                    val now = System.currentTimeMillis()
                    val window = EpgWindow(
                        startMs = now - TimeUnit.HOURS.toMillis(2),
                        endMs = now + TimeUnit.HOURS.toMillis(6),
                    )
                    // Only programmes on channels the user can actually open are useful results.
                    val ids = loadedChannels.mapNotNullTo(HashSet()) { it.epgChannelId }
                    val guide = epg.fetch(configuredProvider, window, ids)
                    guide.channels.forEach { channel ->
                        programmes.addAll(guide.programmesFor(channel.id))
                    }
                }
            }
            runCatching {
                for (league in sports.leagues().take(3)) {
                    games.addAll(sports.scoreboard(league.id).games)
                }
            }

            channels = loadedChannels
            // First channel wins a duplicate EPG id, matching the ranked order the index uses.
            channelByEpgId = loadedChannels
                .filter { !it.epgChannelId.isNullOrBlank() }
                .associateByTo(HashMap()) { it.epgChannelId!! }
            programmesByEpgId = programmes.groupBy { it.channelId }
            // First channel wins a duplicate stream id (they should be unique per provider).
            channelByStreamId = loadedChannels.associateBy { it.streamId }

            index = SearchIndex(channels = loadedChannels, programmes = programmes, games = games)
            // Re-run whatever was typed while the index was still loading, and re-bind the
            // watched history now that the channel list (logos, EPG ids) is available.
            _state.update {
                it.copy(
                    loading = false,
                    results = index.search(it.query),
                    recentChannels = resolveRecentChannels(rawWatched),
                )
            }
        }
    }

    fun run(query: String) {
        _state.update { it.copy(query = query, results = index.search(query)) }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }

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
        SportsRepository.matchChannels(game, channels)

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
