package com.livewire.tv.feature.providers.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The main provider is down and the screens are using the backup instead. */
data class Failover(val primary: ProviderConfig, val backup: ProviderConfig)

/**
 * Picks the provider the screens use: the first saved one, or the provider marked as backup while
 * the first one isn't answering. Only an Xtream main provider is checked (a login request, cheap);
 * with no backup set nothing is checked at all. The answer is reused for [upTtlMs] while the main
 * provider is up and [downTtlMs] while it's down, so returning to a screen doesn't wait on a check
 * every time, and the app moves back to the main provider soon after it recovers.
 */
class ProviderFailover(
    private val check: suspend (ProviderConfig) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val upTtlMs: Long = 10 * 60_000L,
    private val downTtlMs: Long = 2 * 60_000L,
) {
    private val mutex = Mutex()
    private var checkedId: String? = null
    private var checkedAt = 0L
    private var primaryUp = true

    /** Set when the last [choose] fell back to the backup, null otherwise. */
    var failover: Failover? = null
        private set

    suspend fun choose(all: List<ProviderConfig>, backupId: String?): ProviderConfig? = mutex.withLock {
        val primary = all.firstOrNull() ?: return@withLock null.also { failover = null }
        val backup = backupOf(all, backupId)
        if (backup == null || primary.type != ProviderType.XTREAM) {
            failover = null
            return@withLock primary
        }
        val now = clock()
        val ttl = if (primaryUp) upTtlMs else downTtlMs
        if (checkedId != primary.id || now - checkedAt >= ttl) {
            primaryUp = runCatching { check(primary) }.getOrDefault(false)
            checkedId = primary.id
            checkedAt = now
        }
        if (primaryUp) {
            failover = null
            primary
        } else {
            failover = Failover(primary, backup)
            backup
        }
    }

    /** A screen saw the provider fail outright: check again on the next [choose]. */
    fun recheck() {
        checkedAt = 0L
    }

    companion object {
        /** The saved backup, when it's set and isn't the active (first) provider. */
        fun backupOf(all: List<ProviderConfig>, backupId: String?): ProviderConfig? =
            backupId?.let { id -> all.drop(1).firstOrNull { it.id == id } }
    }
}
