package com.livewire.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.XtreamClient
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One rail: a category name + its channels. */
data class ChannelRail(
    val categoryId: String,
    val title: String,
    val channels: List<LiveChannel>,
)

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val rails: List<ChannelRail> = emptyList(),
    val guideLoaded: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val client: XtreamClient,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var streamExt: String = "ts"
    private var showNowPlaying: Boolean = true

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val s = settings.settings.first()
            streamExt = s.streamFormat.ext
            showNowPlaying = s.showNowPlayingOnCards

            val providers = storage.load()
            if (providers.isEmpty()) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            provider = providers.first()
            try {
                val cats = client.liveCategories(provider!!)
                val rails = cats.take(6).mapNotNull { cat ->
                    val channels = client.liveChannels(provider!!, categoryId = cat.id)
                    if (channels.isEmpty()) null
                    else ChannelRail(categoryId = cat.id, title = cat.name, channels = channels)
                }
                _state.update { it.copy(loading = false, rails = rails) }
                // EPG only loaded when the now-playing setting is on (skips work + network when off).
                if (showNowPlaying) {
                    guide = runCatching { epg.fetch(provider!!) }.getOrNull()
                    if (guide != null) _state.update { it.copy(guideLoaded = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Failed to load channels: ${e.message}") }
            }
        }
    }

    fun nowPlaying(channel: LiveChannel): EpgProgramme? {
        if (!showNowPlaying) return null
        val g = guide ?: return null
        val id = channel.epgChannelId ?: return null
        return g.nowPlaying(id)
    }

    fun streamUrl(channel: LiveChannel): String? =
        provider?.liveStreamUrl(channel.streamId, ext = streamExt)
}
