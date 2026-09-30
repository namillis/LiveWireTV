package com.livewire.tv.feature.onboarding

import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.feature.providers.domain.ProviderType

/**
 * A connection failure in plain language, plus the field the user should fix.
 *
 * The onboarding form maps each case to a headline and a sub-line (see [message] /
 * [detail]) and moves focus to [focusField]. Kept as a sealed type — not a string —
 * so the mapping is exhaustive and unit-testable, and so the UI can decide layout
 * (banner vs field error) per case.
 */
sealed interface ConnectError {
    /** The field to focus when this error shows, or null to leave focus where it is. */
    val focusField: ProviderInputValidator.Field?
    val message: String
    val detail: String?

    /** DNS failure, timeout or any other "couldn't reach the server". */
    data object Unreachable : ConnectError {
        override val focusField = ProviderInputValidator.Field.URL
        override val message = "Can't reach the server"
        override val detail = "Check the server address and your network, then try Connect again."
    }

    /** Xtream auth = 0: the username or password is wrong. */
    data object BadCredentials : ConnectError {
        override val focusField = ProviderInputValidator.Field.PASSWORD
        override val message = "Wrong username or password"
        override val detail = "Check the details your provider sent, then try Connect again."
    }

    /** The account authenticated but is expired or disabled. */
    data class AccountInactive(val status: String?) : ConnectError {
        override val focusField = null
        override val message = "This account is ${status?.lowercase() ?: "not active"}"
        override val detail = "Contact your provider to renew or re-enable it."
    }

    /** The playlist or panel loaded but exposed no live channels. */
    data object NoChannels : ConnectError {
        override val focusField = ProviderInputValidator.Field.URL
        override val message = "No live channels found"
        override val detail = "This provider returned no live channels. Check the link is correct."
    }

    companion object {
        /**
         * Map a finished [ProviderAuthResult] (or [validateOk] == false) to a typed
         * error. Xtream distinguishes bad credentials from an inactive account by
         * whether it authenticated; a failed auth with a known-inactive [status] is
         * treated as [AccountInactive], otherwise [BadCredentials]. Anything the
         * provider layer reports as a reachability problem falls back to
         * [Unreachable]. M3U has no auth step, so a failure there is either
         * [Unreachable] (couldn't load) or [NoChannels] (loaded, but empty).
         *
         * @param reachable false when the provider layer's message indicates it
         *   never reached the server (e.g. DNS/timeout), true otherwise.
         */
        fun from(
            type: ProviderType,
            result: ProviderAuthResult,
            reachable: Boolean,
        ): ConnectError {
            if (result.ok) return NoChannels // ok=true reaching here means "no channels"
            if (!reachable) return Unreachable
            return when (type) {
                ProviderType.XTREAM -> {
                    val status = result.status
                    if (status != null && !status.equals("Active", ignoreCase = true)) {
                        AccountInactive(status)
                    } else {
                        BadCredentials
                    }
                }
                // M3U: reached the server but the result is not ok -> empty playlist.
                ProviderType.M3U -> NoChannels
            }
        }

        /**
         * A provider-layer message string counts as "reached the server" unless it is
         * one of the phrases the provider layer uses for a genuine reachability failure
         * (DNS / timeout / no response). Matched as specific phrases, not loose keywords,
         * so an auth-failure message that happens to mention "URL" is NOT misread as
         * unreachable.
         */
        fun looksReachable(message: String?): Boolean {
            if (message == null) return true
            val m = message.lowercase()
            val unreachablePhrases = listOf(
                "could not reach",
                "could not load",
                "check the url and network",
                "empty response",
                "no user_info",
                "timed out",
                "timeout",
            )
            return unreachablePhrases.none { it in m }
        }
    }
}
