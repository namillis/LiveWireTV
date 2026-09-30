package com.livewire.tv.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.feature.providers.domain.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State for the first-run wizard. The step and the non-secret draft fields survive
 * process death via [SavedStateHandle]; the password never does (see below).
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val type: ProviderType = ProviderType.XTREAM,
    // Non-secret draft fields (safe to persist in SavedStateHandle).
    val name: String = ProviderDraft.DEFAULT_NAME,
    val url: String = "",
    val username: String = "",
    val epgUrl: String = "",
    // Password is held here in memory only, NEVER in SavedStateHandle.
    val password: String = "",
    val attempted: Boolean = false,
    val busy: Boolean = false,
    val connectPhase: ConnectPhase = ConnectPhase.SIGNING_IN,
    val error: ConnectError? = null,
    val summary: ProviderSummary? = null,
    val success: Boolean = false,
) {
    val isM3u: Boolean get() = type == ProviderType.M3U

    fun draft() = ProviderDraft(
        type = type, name = name, url = url,
        username = username, password = password, epgUrl = epgUrl,
    )
}

/**
 * Drives onboarding as a step machine (Welcome -> Type -> Details -> Connecting -> Done).
 *
 * Persistence: the step and every non-secret field are mirrored into [SavedStateHandle]
 * so a rotation or process death keeps the user's place and typed values. The password is
 * deliberately excluded — it lives only in the in-memory [_state] — so a killed process
 * re-asks for it rather than persisting a secret (plan section 1, step 3).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private fun restore(): OnboardingUiState = OnboardingUiState(
        step = handle.get<String>(KEY_STEP)?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
            ?: OnboardingStep.WELCOME,
        type = handle.get<String>(KEY_TYPE)?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() }
            ?: ProviderType.XTREAM,
        name = handle.get<String>(KEY_NAME) ?: ProviderDraft.DEFAULT_NAME,
        url = handle.get<String>(KEY_URL).orEmpty(),
        username = handle.get<String>(KEY_USER).orEmpty(),
        epgUrl = handle.get<String>(KEY_EPG).orEmpty(),
        // password intentionally not restored
    ).let { restored ->
        // A restored CONNECTING/DONE step has no live result after process death, so fall
        // back to the form; the user re-runs Connect (the password is gone anyway).
        if (restored.step == OnboardingStep.CONNECTING || restored.step == OnboardingStep.DONE) {
            restored.copy(step = OnboardingStep.DETAILS)
        } else {
            restored
        }
    }

    private fun persist(s: OnboardingUiState) {
        handle[KEY_STEP] = s.step.name
        handle[KEY_TYPE] = s.type.name
        handle[KEY_NAME] = s.name
        handle[KEY_URL] = s.url
        handle[KEY_USER] = s.username
        handle[KEY_EPG] = s.epgUrl
        // password NOT persisted
    }

    private fun set(transform: (OnboardingUiState) -> OnboardingUiState) {
        _state.update { transform(it).also(::persist) }
    }

    // ── Navigation ──

    fun goToType() = set { it.copy(step = OnboardingStep.TYPE) }

    fun selectType(type: ProviderType) = set {
        if (it.type == type) it else it.copy(type = type, error = null, attempted = false)
    }

    fun goToDetailsWith(type: ProviderType) = set {
        it.copy(step = OnboardingStep.DETAILS, type = type, error = null, attempted = false)
    }

    /** Back from DETAILS returns to TYPE; from DONE returns to DETAILS keeping values. */
    fun back(): Boolean {
        val s = _state.value
        return when (s.step) {
            OnboardingStep.TYPE -> { set { it.copy(step = OnboardingStep.WELCOME) }; true }
            OnboardingStep.DETAILS -> { set { it.copy(step = OnboardingStep.TYPE, error = null) }; true }
            OnboardingStep.CONNECTING, OnboardingStep.DONE ->
                { set { it.copy(step = OnboardingStep.DETAILS, error = null, summary = null) }; true }
            OnboardingStep.WELCOME -> false // let the platform exit the app
        }
    }

    // ── Field edits ──

    fun setName(v: String) = set { it.copy(name = v) }
    fun setUsername(v: String) = set { it.copy(username = v) }
    fun setEpgUrl(v: String) = set { it.copy(epgUrl = v, error = null) }
    fun setPassword(v: String) = set { it.copy(password = v) }

    /**
     * Set the server/playlist URL. For Xtream, if the value is a full pasted
     * `get.php` / `player_api.php` link, split it into server + username + password
     * so the user can paste one link (plan section 1, step 5).
     */
    fun setUrl(v: String) = set { s ->
        if (!s.isM3u) {
            ProviderInputValidator.parseXtreamLink(v)?.let { creds ->
                return@set s.copy(
                    url = creds.server,
                    username = creds.username,
                    password = creds.password,
                    error = null,
                )
            }
        }
        s.copy(url = v, error = null)
    }

    // ── Connect ──

    /**
     * Validate locally; on success move to CONNECTING, validate against the provider,
     * fetch the channel count for the summary, then move to DONE. On failure map the
     * result to a typed [ConnectError] and return to DETAILS with the field focused.
     */
    fun connect() {
        val s = _state.value
        if (s.busy) return
        set { it.copy(attempted = true) }
        val draft = _state.value.draft()
        ProviderInputValidator.validate(draft)?.let { problem ->
            set {
                it.copy(
                    error = when (problem.field) {
                        ProviderInputValidator.Field.URL -> ConnectError.Unreachable
                        else -> ConnectError.BadCredentials
                    },
                )
            }
            // Local validation already focuses the field via the form; surface the message inline.
            return
        }

        set { it.copy(step = OnboardingStep.CONNECTING, busy = true, error = null, connectPhase = ConnectPhase.SIGNING_IN) }
        viewModelScope.launch {
            val cfg = draft.toConfig(UUID.randomUUID().toString())
            val auth = providers.validate(cfg)
            if (!auth.ok) {
                val reachable = ConnectError.looksReachable(auth.message)
                set {
                    it.copy(
                        step = OnboardingStep.DETAILS,
                        busy = false,
                        error = ConnectError.from(draft.type, auth, reachable),
                    )
                }
                return@launch
            }
            // Authenticated (or M3U loaded): persist, then load the channel count.
            storage.add(cfg)
            set { it.copy(connectPhase = ConnectPhase.LOADING_CHANNELS) }
            val channels = runCatching { providers.liveChannels(cfg) }.getOrDefault(emptyList())
            if (channels.isEmpty()) {
                // Loaded but empty: remove the just-added provider and report it.
                storage.remove(cfg.id)
                set {
                    it.copy(
                        step = OnboardingStep.DETAILS,
                        busy = false,
                        error = ConnectError.NoChannels,
                    )
                }
                return@launch
            }
            val summary = ProviderSummary(
                status = auth.status,
                expiryEpochSeconds = auth.expiry,
                maxConnections = auth.maxConnections,
                liveChannels = channels.size,
            )
            // Start the guide prefetch but DON'T await it: the guide continues in the
            // background and never blocks reaching Home (plan section 1, step 4).
            startGuidePrefetch(cfg)
            set {
                it.copy(
                    step = OnboardingStep.DONE,
                    busy = false,
                    connectPhase = ConnectPhase.LOADING_GUIDE,
                    summary = summary,
                    error = null,
                )
            }
        }
    }

    private fun startGuidePrefetch(cfg: ProviderConfig) {
        viewModelScope.launch {
            runCatching { epg.fetch(cfg) }
            // Failures are ignored on purpose: a missing/broken guide is not a setup error.
        }
    }

    /** Called from the DONE screen's "Start watching". */
    fun finish() = set { it.copy(success = true) }

    fun clearError() = set { it.copy(error = null) }

    private companion object {
        const val KEY_STEP = "onb_step"
        const val KEY_TYPE = "onb_type"
        const val KEY_NAME = "onb_name"
        const val KEY_URL = "onb_url"
        const val KEY_USER = "onb_user"
        const val KEY_EPG = "onb_epg"
    }
}
