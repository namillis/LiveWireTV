package com.livewire.tv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.search.data.RecentHistoryStore
import com.livewire.tv.feature.search.data.WatchedChannel
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerSourceState(
    val loading: Boolean = true,
    val source: PlaybackSource? = null,
    val error: String? = null,
)

/** Resolves a player-only source after navigation, keeping credentials out of route state. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val providerStorage: ProviderStorage,
    private val providers: ProviderRepository,
    private val settingsStore: SettingsStore,
    private val history: RecentHistoryStore,
) : ViewModel() {

    private val _source = MutableStateFlow(PlayerSourceState())
    val source: StateFlow<PlayerSourceState> = _source.asStateFlow()

    fun resolve(target: PlaybackTarget, title: String = "") {
        _source.update { PlayerSourceState(loading = true) }
        viewModelScope.launch {
            val provider = providerStorage.load().firstOrNull { it.id == target.providerId }
            if (provider == null) {
                _source.update {
                    PlayerSourceState(loading = false, error = "This provider is no longer configured.")
                }
                return@launch
            }
            val streamFormat = settingsStore.settings.first().streamFormat
            val resolved = runCatching {
                providers.playbackSource(provider, target.streamId, streamFormat.ext)
            }
            _source.update {
                resolved.fold(
                    onSuccess = { playable ->
                        if (playable == null) {
                            PlayerSourceState(loading = false, error = "This channel is no longer in the playlist.")
                        } else {
                            PlayerSourceState(loading = false, source = playable)
                        }
                    },
                    onFailure = {
                        PlayerSourceState(loading = false, error = "Could not load this channel. Check the provider and network.")
                    },
                )
            }
            // Playback started (source resolved): remember this channel for Search's "Jump
            // back in". Recorded by stream id; the logo/now-playing are re-resolved there
            // against the live channel list, so only the reference + a display name is stored.
            if (resolved.getOrNull() != null) {
                history.recordWatched(
                    WatchedChannel(
                        providerId = target.providerId,
                        streamId = target.streamId,
                        name = title,
                        ts = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}
