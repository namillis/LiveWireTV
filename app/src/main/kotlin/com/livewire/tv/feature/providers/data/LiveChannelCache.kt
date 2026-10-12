package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Each provider's full live channel list, kept in memory for [ttlMs] so Home, the Guide, Search,
 * Sports and the player share one download instead of each fetching it. Keyed by the provider's
 * id and connection details, so editing a provider's URL or login starts a fresh entry. Concurrent
 * callers for the same provider wait for one load. An empty result is never cached, because the
 * Xtream client returns empty on an HTTP error.
 */
class LiveChannelCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long, val channels: List<LiveChannel>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(cfg: ProviderConfig, load: suspend () -> List<LiveChannel>): List<LiveChannel> {
        val key = keyFor(cfg)
        fresh(key)?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            fresh(key) ?: load().also { if (it.isNotEmpty()) entries[key] = Entry(clock(), it) }
        }
    }

    fun clear() = entries.clear()

    private fun fresh(key: String): List<LiveChannel>? =
        entries[key]?.takeIf { clock() - it.at < ttlMs }?.channels

    private fun keyFor(cfg: ProviderConfig): String =
        listOf(cfg.id, cfg.type.name, cfg.baseUrl, cfg.username, cfg.password).joinToString("\u0000").hashCode()
            .let { "${cfg.id}:$it" }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }
}
