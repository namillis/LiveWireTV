package com.livewire.tv.feature.providers

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

data class ProvidersUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val activeId: String? = null,
    val validating: Boolean = false,
    val formError: String? = null,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val storage: ProviderStorage,
    private val client: XtreamClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    fun load() {
        val all = storage.load()
        _state.update { it.copy(providers = all, activeId = all.firstOrNull()?.id) }
    }

    /** Validate against the panel, then upsert. Calls [onDone] on success. */
    fun addOrUpdate(
        existingId: String?,
        name: String,
        url: String,
        username: String,
        password: String,
        onDone: () -> Unit,
    ) {
        _state.update { it.copy(validating = true, formError = null) }
        viewModelScope.launch {
            val cfg = ProviderConfig(
                id = existingId ?: UUID.randomUUID().toString(),
                name = name.trim().ifEmpty { "My Provider" },
                baseUrl = url.trim().trimEnd('/'),
                username = username.trim(),
                password = password,
            )
            val result = client.authenticate(cfg)
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

    fun remove(id: String) { storage.remove(id); load() }
    fun setActive(id: String) { storage.setActive(id); load() }
}
