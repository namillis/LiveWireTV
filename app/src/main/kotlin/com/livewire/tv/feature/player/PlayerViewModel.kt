package com.livewire.tv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.PlaybackTarget
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
    val streamUrl: String? = null,
    val error: String? = null,
)

/** Resolves a player-only URL after navigation, keeping credentials out of route state. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val providerStorage: ProviderStorage,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _source = MutableStateFlow(PlayerSourceState())
    val source: StateFlow<PlayerSourceState> = _source.asStateFlow()

    fun resolve(target: PlaybackTarget) {
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
            _source.update {
                PlayerSourceState(
                    loading = false,
                    streamUrl = provider.liveStreamUrl(target.streamId, streamFormat.ext),
                )
            }
        }
    }
}
