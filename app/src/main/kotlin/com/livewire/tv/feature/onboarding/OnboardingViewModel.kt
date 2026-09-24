package com.livewire.tv.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
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
    private val providers: ProviderRepository,
    private val storage: ProviderStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Validate locally, then against the provider; store + signal success if OK. */
    fun connect(draft: ProviderDraft) {
        if (_state.value.busy) return
        ProviderInputValidator.validate(draft)?.let { problem ->
            _state.update { it.copy(error = problem.message) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val cfg = draft.toConfig(UUID.randomUUID().toString())
            val result = providers.validate(cfg)
            if (result.ok) {
                storage.add(cfg)
                _state.update { it.copy(busy = false, success = true) }
            } else {
                _state.update { it.copy(busy = false, error = result.message ?: "Could not connect") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
