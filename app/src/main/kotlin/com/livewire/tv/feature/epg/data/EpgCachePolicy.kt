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
     *
     * The key deliberately does NOT include the caller's channel set. A provider has ONE
     * guide; every screen (Guide shows a few categories, Search shows every channel) reads
     * the same downloaded document. The cache stores the parsed superset once and filters it
     * to the caller's channels on read, so opening Search after the Guide — or vice versa —
     * reuses the one cached guide instead of re-downloading the ~64 MB feed under a second key.
     */
    fun keyFor(cfg: ProviderConfig, guideUrl: String?): String {
        val material = "${cfg.id}|${guideUrl.orEmpty()}"
        return sha256Hex(material, bytes = 16)
    }

    /**
     * True when a cached entry parsed for [cachedIds] holds every channel a caller asking for
     * [requested] needs. A null [cachedIds] means the entry was parsed with no channel filter
     * (all channels kept), so it covers any request. A null [requested] means the caller wants
     * every channel, which only an all-channels entry can satisfy.
     */
    fun coversChannels(cachedIds: Set<String>?, requested: Set<String>?): Boolean {
        if (cachedIds == null) return true          // superset: all channels present
        if (requested == null) return false         // caller wants all; a filtered entry cannot serve it
        return cachedIds.containsAll(requested)
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
     * Extra span the parsed window reaches BACK past a caller's start. Search looks 2h into
     * the past; the Guide only 30min. Reaching back the larger amount means a guide parsed for
     * one screen still covers the other, so opening the second screen is a cache hit, not a
     * reload — whichever screen downloaded first.
     */
    val SUPERSET_LEAD_MS: Long = TimeUnit.HOURS.toMillis(2)

    /**
     * Extra span the parsed window reaches FORWARD past a caller's end, on top of the TTL slide.
     * The Guide's window can be several hours; Search's is 6h. Reaching forward this far means a
     * guide parsed for the shorter screen still covers the longer one.
     */
    val SUPERSET_TRAIL_MS: Long = TimeUnit.HOURS.toMillis(6)

    /**
     * Window to parse when downloading for [requested]. The cache holds ONE entry per provider
     * that every screen shares, so the parsed window is a superset: it reaches back [SUPERSET_LEAD_MS]
     * before the request and forward [SUPERSET_TRAIL_MS] plus [ttlMs] past it. The lead/trail make
     * the entry cover a differently-windowed sibling screen (Guide vs Search) in either open order;
     * the TTL slide keeps the same screen reopened later within the TTL a hit as its window advances.
     */
    fun downloadWindow(requested: EpgWindow?, ttlMs: Long = DEFAULT_TTL_MS): EpgWindow? =
        requested?.let {
            EpgWindow(
                startMs = it.startMs - SUPERSET_LEAD_MS,
                endMs = it.endMs + SUPERSET_TRAIL_MS + ttlMs.coerceAtLeast(0L),
            )
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
