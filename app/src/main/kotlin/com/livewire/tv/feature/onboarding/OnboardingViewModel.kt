package com.livewire.tv.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.XtreamClient
import com.livewire.tv.feature.providers.domain.ProviderConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class OnboardingUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val client: XtreamClient,
    private val storage: ProviderStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Validate the entered provider against the panel; store + signal success if OK. */
    fun connect(name: String, url: String, username: String, password: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val cfg = ProviderConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifEmpty { "My Provider" },
                baseUrl = url.trim().trimEnd('/'),
                username = username.trim(),
                password = password,
            )
            val result = client.authenticate(cfg)
            if (result.ok) {
                storage.add(cfg)
                _state.update { it.copy(busy = false, success = true) }
            } else {
                _state.update { it.copy(busy = false, error = result.message ?: "Could not connect") }
            }
        }
    }
}
