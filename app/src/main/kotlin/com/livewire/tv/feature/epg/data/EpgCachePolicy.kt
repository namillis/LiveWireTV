package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgWindow
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
    fun keyFor(cfg: ProviderConfig, guideUrl: String?, channelIds: Set<String>? = null): String {
        // The cached guide is filtered to [channelIds] while parsing, so callers asking for
        // different channel sets (Home/Guide use a few categories, Search uses every channel)
        // must not share an entry: one would silently get the other's programmes only.
        val ids = channelIds?.sorted()?.joinToString(",") ?: "*"
        val material = "${cfg.id}|${guideUrl.orEmpty()}|$ids"
        return sha256Hex(material, bytes = 16)
    }

    /**
     * Short, non-secret hash of the provider id ALONE. It prefixes every cache file name for
     * that provider (`<providerHash>_<keyHash>.json`) so a provider's entries can be found and
     * deleted when it is removed or edited, without ever mapping a file back to a URL or
     * credential. The provider id is a UUID, not a secret, but it is hashed anyway to keep file
     * names uniform and opaque.
     */
    fun providerHashFor(providerId: String): String = sha256Hex(providerId, bytes = 8)

    /** Filename component `<providerHash>_` shared by all of one provider's cache files. */
    fun providerPrefixFor(providerId: String): String = "${providerHashFor(providerId)}_"

    /** On-disk file name for a content [key] belonging to [providerId]: `<providerHash>_<key>.json`. */
    fun fileNameFor(providerId: String, key: String): String = "${providerPrefixFor(providerId)}$key.json"

    /**
     * True when [fileName] is a cache file belonging to [providerId]. Old-format files written
     * before the prefix existed (a bare `<key>.json`) never match, so they are left untouched.
     */
    fun isForProvider(fileName: String, providerId: String): Boolean =
        fileName.startsWith(providerPrefixFor(providerId)) && fileName.endsWith(".json")

    private fun sha256Hex(material: String, bytes: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * True when a guide cached for the window [cachedStartMs]..[cachedEndMs] holds every
     * programme a caller asking for [requested] needs. Programmes outside the cached window
     * were dropped during parsing, so a narrower cached window is a miss, never a hit.
     * Null bounds mean the cached guide was parsed without a window (everything kept).
     */
    fun covers(cachedStartMs: Long?, cachedEndMs: Long?, requested: EpgWindow?): Boolean {
        if (cachedStartMs == null || cachedEndMs == null) return cachedStartMs == null && cachedEndMs == null
        if (requested == null) return false
        return cachedStartMs <= requested.startMs && cachedEndMs >= requested.endMs
    }

    /**
     * Window to parse when downloading for [requested]. The end is pushed out by [ttlMs] so
     * the same screen reopened later within the TTL (its window slides forward with the
     * clock) is still covered and does not re-download. The start needs no widening: later
     * requests of the same shape start later.
     */
    fun downloadWindow(requested: EpgWindow?, ttlMs: Long = DEFAULT_TTL_MS): EpgWindow? =
        requested?.let { EpgWindow(it.startMs, it.endMs + ttlMs.coerceAtLeast(0L)) }

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
