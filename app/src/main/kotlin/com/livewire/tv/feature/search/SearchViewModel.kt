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
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.SportsGame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SearchUiState(
    val loading: Boolean = true,
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val storage: ProviderStorage,
    private val client: ProviderRepository,
    private val epg: EpgRepository,
    private val sports: SportsRepository,
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

            index = SearchIndex(channels = loadedChannels, programmes = programmes, games = games)
            // Re-run whatever was typed while the index was still loading.
            _state.update { it.copy(loading = false, results = index.search(it.query)) }
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
     * The single channel to play when a game row is selected: the best-ranked of the
     * user's own live channels that appear to carry the game (same fusion the Sports
     * picker uses), or null when none match. The screen falls back to opening Sports.
     */
    fun topChannelForGame(game: SportsGame): LiveChannel? =
        SportsRepository.matchChannels(game, channels).firstOrNull()?.channel
}
