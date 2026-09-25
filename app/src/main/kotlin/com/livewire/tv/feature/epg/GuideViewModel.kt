package com.livewire.tv.feature.epg

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

/** One guide row: a channel + programmes overlapping the visible window. */
data class GuideRow(
    val channel: LiveChannel,
    val programmes: List<EpgProgramme>,
)

data class GuideUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<GuideRow> = emptyList(),
    val windowStartMs: Long = 0,
    val windowSpanMs: Long = TimeUnit.HOURS.toMillis(4),
)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val client: ProviderRepository,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val appSettings = settings.settings.first()
            val spanMs = TimeUnit.HOURS.toMillis(appSettings.guideWindowHours.toLong())
            val now = System.currentTimeMillis()
            val halfHour = TimeUnit.MINUTES.toMillis(30)
            val windowStart = (now / halfHour) * halfHour - halfHour
            val window = EpgWindow(windowStart, windowStart + spanMs)
            _state.update { it.copy(windowSpanMs = spanMs) }

            val providers = storage.load()
            if (providers.isEmpty()) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            provider = providers.first()
            try {
                val categories = client.liveCategories(provider!!)
                val channels = buildList {
                    for (category in categories.take(6)) {
                        addAll(client.liveChannels(provider!!, categoryId = category.id))
                    }
                }
                // A missing or broken guide should not hide the channel list (M3U
                // playlists often ship without one); rows then show no programmes.
                val ids = channels.mapNotNullTo(HashSet()) { it.epgChannelId }
                val guide = runCatching { epg.fetch(provider!!, window, ids) }.getOrNull()
                val rows = channels.map { channel ->
                    GuideRow(
                        channel = channel,
                        programmes = guide?.let { g ->
                            channel.epgChannelId?.let(g::programmesFor)
                        } ?: emptyList(),
                    )
                }
                _state.update {
                    it.copy(loading = false, rows = rows, windowStartMs = windowStart)
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(loading = false, error = "Could not load the guide. Check the provider and network.")
                }
            }
        }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }
}
