package com.livewire.tv.core.player

/**
 * HTTP answers that mean the provider turned down a new connection for now, not that the
 * stream is broken. The player retries these quietly a couple of times; on the pool each retry
 * carries `try=N&why=refused`, so the pool moves to another source.
 */
object RefusedCodes {

    /**
     * 401/403 come from single-connection logins for a moment after the old stream closes, 429
     * is a rate limit, and 456 and 460 are what some Xtream panels answer when the login's
     * connection limit is reached.
     */
    val CODES: Set<Int> = setOf(401, 403, 429, 456, 460)

    fun isRefusal(code: Int?): Boolean = code != null && code in CODES
}
