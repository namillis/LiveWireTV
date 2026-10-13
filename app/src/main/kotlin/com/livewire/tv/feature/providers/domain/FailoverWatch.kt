package com.livewire.tv.feature.providers.domain

import kotlinx.coroutines.delay

/**
 * Lets a screen that loaded from the backup notice when the main provider is back. The screen
 * keeps no timer of its own: [ProviderFailover] already decides how often the main provider is
 * really checked, so asking it every [POLL_MS] costs at most one login request per its down TTL.
 */
object FailoverWatch {

    /** How often a screen on the backup asks which provider it should be using. */
    const val POLL_MS = 30_000L

    /**
     * Suspends while [current] keeps answering [using], then returns the provider to switch to.
     * Asks every [everyMs]. Returns null when no provider is configured any more.
     */
    suspend fun awaitChange(
        using: ProviderConfig,
        everyMs: Long = POLL_MS,
        current: suspend () -> ProviderConfig?,
    ): ProviderConfig? {
        while (true) {
            delay(everyMs)
            val now = current()
            if (now?.id != using.id) return now
        }
    }
}
