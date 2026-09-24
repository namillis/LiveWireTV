package com.livewire.tv.feature.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.XtreamClient
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
import javax.inject.Inject

data class SportsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val leagues: List<SportsLeague> = emptyList(),
    val selectedLeagueId: String? = null,
    val scoreboard: SportsScoreboard? = null,
    val standings: SportsStandings? = null,
)

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: SportsRepository,
    private val storage: ProviderStorage,
    private val client: XtreamClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var channels: List<LiveChannel> = emptyList()

    fun init() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val leagues = repository.leagues()
                val selected = leagues.firstOrNull()?.id
                _state.update { it.copy(leagues = leagues, selectedLeagueId = selected) }

                // Load the user's channels once for game→channel fusion (best-effort).
                storage.load().firstOrNull()?.let { prov ->
                    provider = prov
                    channels = runCatching {
                        buildList {
                            for (cat in client.liveCategories(prov).take(8)) {
                                addAll(client.liveChannels(prov, categoryId = cat.id))
                            }
                        }
                    }.getOrDefault(emptyList())
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
        SportsRepository.matchChannels(game, channels)

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
