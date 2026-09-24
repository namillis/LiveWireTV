package com.livewire.tv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.XtreamClient
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
    private val client: XtreamClient,
    private val epg: EpgRepository,
    private val sports: SportsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var index = SearchIndex()

    fun init() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val channels = mutableListOf<LiveChannel>()
            val programmes = mutableListOf<EpgProgramme>()
            val games = mutableListOf<SportsGame>()

            storage.load().firstOrNull()?.let { configuredProvider ->
                provider = configuredProvider
                runCatching {
                    for (category in client.liveCategories(configuredProvider).take(8)) {
                        channels.addAll(client.liveChannels(configuredProvider, categoryId = category.id))
                    }
                }
                runCatching {
                    val now = System.currentTimeMillis()
                    val window = EpgWindow(
                        startMs = now - TimeUnit.HOURS.toMillis(2),
                        endMs = now + TimeUnit.HOURS.toMillis(24),
                    )
                    val guide = epg.fetch(configuredProvider, window)
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

            index = SearchIndex(channels = channels, programmes = programmes, games = games)
            _state.update { it.copy(loading = false) }
        }
    }

    fun run(query: String) {
        _state.update { it.copy(query = query, results = index.search(query)) }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
