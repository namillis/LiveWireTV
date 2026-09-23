package com.livewire.tv.feature.epg

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
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One guide row: a channel + the programmes overlapping the visible window. */
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
    private val client: XtreamClient,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var streamExt: String = "ts"

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val s = settings.settings.first()
            streamExt = s.streamFormat.ext
            val spanMs = TimeUnit.HOURS.toMillis(s.guideWindowHours.toLong())
            _state.update { it.copy(windowSpanMs = spanMs) }

            val providers = storage.load()
            if (providers.isEmpty()) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            provider = providers.first()
            try {
                val cats = client.liveCategories(provider!!)
                val channels = buildList {
                    for (cat in cats.take(6)) addAll(client.liveChannels(provider!!, categoryId = cat.id))
                }
                val guide: EpgGuide = epg.fetch(provider!!)

                val now = System.currentTimeMillis()
                val half = TimeUnit.MINUTES.toMillis(30)
                val windowStart = (now / half) * half - half
                val windowEnd = windowStart + spanMs

                // LEAN DISCIPLINE #2: window in DATA — keep only programmes overlapping
                // the visible window, not the whole guide, per channel.
                val rows = channels.map { ch ->
                    val progs = ch.epgChannelId?.let { id ->
                        guide.programmesFor(id).filter { it.stopMs > windowStart && it.startMs < windowEnd }
                    } ?: emptyList()
                    GuideRow(channel = ch, programmes = progs)
                }
                _state.update { it.copy(loading = false, rows = rows, windowStartMs = windowStart) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Failed to load guide: ${e.message}") }
            }
        }
    }

    fun streamUrl(channel: LiveChannel): String? =
        provider?.liveStreamUrl(channel.streamId, ext = streamExt)
}
