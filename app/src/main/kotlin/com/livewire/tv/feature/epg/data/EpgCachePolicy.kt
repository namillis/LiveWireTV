package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.providers.domain.ProviderConfig
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pure cache policy for the parsed EPG guide. No Android or I/O here, so key derivation
 * and expiry are unit-testable in isolation.
 *
 * A provider's `xmltv.php` guide is large (tens of MB) and slow to download, but it
 * changes only over hours. Caching the parsed guide per provider turns every open after
 * the first into an in-memory (or on-disk) hit, so the guide appears instantly.
 */
object EpgCachePolicy {

    /** Default freshness window for a cached guide. Guides update on the order of hours. */
    val DEFAULT_TTL_MS: Long = TimeUnit.HOURS.toMillis(3)

    /**
     * Stable, non-secret cache key for a provider. The guide URL embeds credentials, so the
     * key is a one-way hash of the identity that determines guide content (id + guide URL),
     * never the URL itself. Keying on the guide URL as well as the id means changing a
     * provider's credentials or endpoint invalidates its cache.
     */
    fun keyFor(cfg: ProviderConfig, guideUrl: String?): String {
        val material = "${cfg.id}|${guideUrl.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * True when an entry stored at [cachedAtMs] is still fresh at [nowMs] for the given [ttlMs].
     * A non-positive TTL disables caching (nothing is ever fresh); a cached timestamp in the
     * future (clock moved back) is treated as stale rather than fresh forever.
     */
    fun isFresh(cachedAtMs: Long, nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        if (ttlMs <= 0L) return false
        val age = nowMs - cachedAtMs
        return age in 0 until ttlMs
    }
}
