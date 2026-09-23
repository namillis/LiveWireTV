package com.livewire.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.settings.data.AppSettings
import com.livewire.tv.feature.settings.data.SettingsStore
import com.livewire.tv.feature.settings.data.StreamFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setStreamFormat(f: StreamFormat) = viewModelScope.launch { store.setStreamFormat(f) }
    fun setGuideWindowHours(h: Int) = viewModelScope.launch { store.setGuideWindowHours(h) }
    fun setShowNowPlaying(v: Boolean) = viewModelScope.launch { store.setShowNowPlaying(v) }
}
