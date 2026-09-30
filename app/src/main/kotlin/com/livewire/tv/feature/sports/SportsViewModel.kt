package com.livewire.tv.feature.sports

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
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.SportsStandings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SportsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val leagues: List<SportsLeague> = emptyList(),
    val selectedLeagueId: String? = null,
    val scoreboard: SportsScoreboard? = null,
    val standings: SportsStandings? = null,
    // Bumped when the guide arrives, so an open channel picker re-ranks with it.
    val guideVersion: Int = 0,
)

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: SportsRepository,
    private val storage: ProviderStorage,
    private val client: ProviderRepository,
    private val epg: EpgRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var channels: List<LiveChannel> = emptyList()
    private var guide: Map<String, List<EpgProgramme>> = emptyMap()

    fun init() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val leagues = repository.leagues()
                val selected = leagues.firstOrNull()?.id
                _state.update { it.copy(leagues = leagues, selectedLeagueId = selected) }

                // Load the user's channels for game→channel fusion (best-effort), alongside
                // the scoreboard rather than before it. Every channel, not a sample of
                // categories: this provider lists no sports in its first 8 categories, so the old
                // first-8 sample held no ESPN, Prime or NHL channel at all.
                storage.load().firstOrNull()?.let { prov ->
                    provider = prov
                    launch {
                        channels = runCatching { client.liveChannels(prov) }.getOrDefault(emptyList())
                        guide = loadGuide(prov, channels)
                        if (guide.isNotEmpty()) _state.update { it.copy(guideVersion = it.guideVersion + 1) }
                    }
                }

                if (selected != null) loadLeague(selected)
                else _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Could not load sports. Check your network and try again.") }
            }
        }
    }

    fun selectLeague(leagueId: String) {
        if (leagueId == _state.value.selectedLeagueId && _state.value.scoreboard != null) return
        _state.update { it.copy(selectedLeagueId = leagueId, loading = true, error = null) }
        viewModelScope.launch { loadLeague(leagueId) }
    }

    private suspend fun loadLeague(leagueId: String) {
        try {
            val sb = repository.scoreboard(leagueId)
            val st = runCatching { repository.standings(leagueId) }.getOrNull() // best-effort
            _state.update { it.copy(loading = false, scoreboard = sb, standings = st) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Could not load this league. Try again.") }
        }
    }

    fun channelsFor(game: SportsGame): List<ChannelMatch> =
        SportsRepository.matchChannels(game, channels, guide)

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
        fetched.channels.associate { c ->
            c.id to fetched.programmesFor(c.id).map { p ->
                if (SportsRepository.looksLikeGame(p.title)) p else p.copy(description = null)
            }
        }
    }.getOrDefault(emptyMap())

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
