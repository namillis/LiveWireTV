package com.livewire.tv.feature.onboarding

/** The four screens of the first-run wizard, in order (design: Option 1 wizard). */
enum class OnboardingStep { WELCOME, TYPE, DETAILS, CONNECTING, DONE }

/**
 * What the success screen shows once a provider is connected. All fields except
 * [liveChannels] are optional because M3U has no account status/expiry/connections
 * and some Xtream panels omit them; the UI hides what is null.
 *
 * @param liveChannels the count from [com.livewire.tv.feature.providers.data.ProviderRepository.liveChannels].
 * @param expiryEpochSeconds Xtream `exp_date` (epoch seconds), or null.
 * @param maxConnections Xtream `max_connections`, or null.
 */
data class ProviderSummary(
    val status: String? = null,
    val expiryEpochSeconds: Long? = null,
    val maxConnections: Int? = null,
    val liveChannels: Int = 0,
)

/** The three connecting sub-steps, each shown with its own state on the DONE/CONNECTING screen. */
enum class ConnectPhase { SIGNING_IN, LOADING_CHANNELS, LOADING_GUIDE }
