package com.livewire.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.settings.data.AppSettings
import com.livewire.tv.feature.settings.data.SettingsStore
import com.livewire.tv.feature.settings.data.StreamFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val providers: ProviderStorage,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _providerSummary = MutableStateFlow("")
    /** The active provider's label for the header and the Providers row; "" until loaded. */
    val providerSummary: StateFlow<String> = _providerSummary.asStateFlow()

    /** Re-read on every visit, so a change made on the Providers screen shows on return. */
    fun refreshProviderSummary() = viewModelScope.launch {
        val all = withContext(Dispatchers.IO) { runCatching { providers.load() }.getOrDefault(emptyList()) }
        _providerSummary.value = SettingsFormat.providerSummary(all)
    }

    fun setStreamFormat(f: StreamFormat) = viewModelScope.launch { store.setStreamFormat(f) }
    fun setGuideWindowHours(h: Int) = viewModelScope.launch { store.setGuideWindowHours(h) }
    fun setShowNowPlaying(v: Boolean) = viewModelScope.launch { store.setShowNowPlaying(v) }
}
