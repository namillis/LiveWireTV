package com.livewire.tv.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.ProviderConfig
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

data class ProvidersUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val activeId: String? = null,
    val validating: Boolean = false,
    val formError: String? = null,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val storage: ProviderStorage,
    private val repository: ProviderRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    fun load() {
        val all = storage.load()
        _state.update { it.copy(providers = all, activeId = all.firstOrNull()?.id) }
    }

    /** Validate locally and against the provider, then upsert. Calls [onDone] on success. */
    fun addOrUpdate(existingId: String?, draft: ProviderDraft, onDone: () -> Unit) {
        if (_state.value.validating) return
        ProviderInputValidator.validate(draft)?.let { problem ->
            _state.update { it.copy(formError = problem.message) }
            return
        }
        _state.update { it.copy(validating = true, formError = null) }
        viewModelScope.launch {
            val cfg = draft.toConfig(existingId ?: UUID.randomUUID().toString())
            val result = repository.validate(cfg)
            if (result.ok) {
                storage.add(cfg)
                load()
                _state.update { it.copy(validating = false) }
                onDone()
            } else {
                _state.update { it.copy(validating = false, formError = result.message ?: "Could not connect") }
            }
        }
    }

    fun clearFormError() = _state.update { it.copy(formError = null) }

    fun remove(id: String) {
        storage.remove(id)
        viewModelScope.launch { repository.forget() }
        load()
    }

    fun setActive(id: String) { storage.setActive(id); load() }
}
