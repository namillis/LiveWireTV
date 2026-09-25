package com.livewire.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
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
    private val client: ProviderRepository,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var showNowPlaying: Boolean = true

    fun load() {
        _state.update { it.copy(loading = true, error = null, guideLoaded = false) }
        viewModelScope.launch {
            val appSettings = settings.settings.first()
            showNowPlaying = appSettings.showNowPlayingOnCards

            val providers = storage.load()
            if (providers.isEmpty()) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            provider = providers.first()
            try {
                val categories = client.liveCategories(provider!!)
                val rails = categories.take(6).mapNotNull { category ->
                    val channels = client.liveChannels(provider!!, categoryId = category.id)
                    if (channels.isEmpty()) null else ChannelRail(category.id, category.name, channels)
                }
                _state.update { it.copy(loading = false, rails = rails) }

                if (showNowPlaying) {
                    val now = System.currentTimeMillis()
                    val window = EpgWindow(
                        startMs = now - TimeUnit.HOURS.toMillis(2),
                        endMs = now + TimeUnit.HOURS.toMillis(12),
                    )
                    val ids = rails.flatMapTo(HashSet()) { rail -> rail.channels.mapNotNull { it.epgChannelId } }
                    guide = runCatching { epg.fetch(provider!!, window, ids) }.getOrNull()
                    if (guide != null) _state.update { it.copy(guideLoaded = true) }
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(loading = false, error = "Could not load channels. Check the provider and network.")
                }
            }
        }
    }

    fun nowPlaying(channel: LiveChannel): EpgProgramme? {
        if (!showNowPlaying) return null
        return channel.epgChannelId?.let { guide?.nowPlaying(it) }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
