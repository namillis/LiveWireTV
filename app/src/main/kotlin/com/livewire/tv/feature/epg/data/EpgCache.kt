package com.livewire.tv.feature.epg.data

import android.content.Context
import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** On-disk shape of a cached, parsed guide. Kept separate from the domain models so the
 *  domain stays free of serialization concerns. */
@Serializable
private data class CachedProgramme(
    val c: String, val s: Long, val e: Long, val t: String,
    val d: String? = null, val g: String? = null,
)

@Serializable
private data class CachedChannel(val id: String, val n: String, val i: String? = null)

@Serializable
private data class CachedGuide(
    val cachedAtMs: Long,
    val channels: List<CachedChannel>,
    val programmes: List<CachedProgramme>,
    // Window the guide was parsed with; programmes outside it are not in the entry.
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
)

/**
 * Caches the parsed EPG guide per provider so the guide opens instantly after the first
 * load. A provider's `xmltv.php` is tens of MB and takes 10-20s to download; it changes
 * only over hours, so a short-TTL cache removes almost all of that cost.
 *
 * Two layers: an in-memory entry (instant, survives while the app lives) and an on-disk
 * JSON entry (survives process death, keyed by a non-secret hash of the provider). A
 * per-key [Mutex] also single-flights concurrent loads: if the guide is opened twice in
 * quick succession, only one download runs and both callers share its result.
 */
@Singleton
class EpgCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dir: File = File(context.cacheDir, "epg_cache")
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Mutex()
    private val locks = HashMap<String, Mutex>()
    private val memory = HashMap<String, CachedGuide>()

    /** Per-key lock, so two providers can load in parallel but one provider single-flights. */
    private suspend fun lockFor(entry: EntryId): Mutex = lock.withLock {
        locks.getOrPut(entry.mapKey) { Mutex() }
    }

    /**
     * Runs [load] under this provider's single-flight lock. If a fresh cached guide exists
     * (memory first, then disk) whose parsed window covers [window], it is returned without
     * calling [load] at all. Otherwise [load] downloads+parses the guide for [parsedWindow],
     * and the result is stored in both layers together with that window.
     *
     * @param ttlMs freshness window; a non-positive value disables the cache (always loads).
     * @param nowMs injectable clock for tests.
     */
    suspend fun getOrLoad(
        key: String,
        providerId: String,
        window: EpgWindow?,
        parsedWindow: EpgWindow?,
        ttlMs: Long = EpgCachePolicy.DEFAULT_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
        load: suspend () -> EpgGuide,
    ): EpgGuide {
        val entry = EntryId(providerId, key)
        return lockFor(entry).withLock {
            read(entry, window, nowMs, ttlMs)?.let { return@withLock it }
            val guide = load()
            write(entry, guide, parsedWindow, nowMs)
            guide
        }
    }

    /** Identifies one cache entry: its content [key], namespaced by the [providerId] that owns it. */
    private data class EntryId(val providerId: String, val key: String) {
        /** Stable memory-map / lock key, prefixed so a provider's entries can be matched together. */
        val mapKey: String get() = EpgCachePolicy.providerPrefixFor(providerId) + key
    }

    /** Fresh cached guide for [entry] that covers [window], or null when absent, stale or too narrow. */
    private suspend fun read(entry: EntryId, window: EpgWindow?, nowMs: Long, ttlMs: Long): EpgGuide? {
        fun usable(c: CachedGuide) =
            EpgCachePolicy.isFresh(c.cachedAtMs, nowMs, ttlMs) &&
                EpgCachePolicy.covers(c.windowStartMs, c.windowEndMs, window)
        memory[entry.mapKey]?.let { if (usable(it)) return it.toGuide() }
        val onDisk = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<CachedGuide>(fileFor(entry).readText()) }.getOrNull()
        } ?: return null
        if (!usable(onDisk)) return null
        memory[entry.mapKey] = onDisk
        return onDisk.toGuide()
    }

    private suspend fun write(entry: EntryId, guide: EpgGuide, parsedWindow: EpgWindow?, nowMs: Long) {
        val cached = guide.toCached(nowMs, parsedWindow)
        memory[entry.mapKey] = cached
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                fileFor(entry).writeText(json.encodeToString<CachedGuide>(cached))
            }
        }
    }

    /** Drops all cached guides, for example after a provider is deleted. */
    suspend fun clear() = lock.withLock {
        memory.clear()
        locks.clear()
        withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
    }

    /**
     * Drops only [providerId]'s cached guides, in memory and on disk, leaving every other
     * provider's entries intact. Call when a provider is deleted, or edited in a way that
     * changes its guide (credentials/endpoint) so its old entries are unreachable anyway.
     * Old-format files without a provider prefix are left untouched.
     */
    suspend fun clearProvider(providerId: String) = lock.withLock {
        val prefix = EpgCachePolicy.providerPrefixFor(providerId)
        memory.keys.removeAll { it.startsWith(prefix) }
        // Locks stay: dropping one while a load holds it would let the next open start a
        // second, concurrent download for the same entry.
        withContext(Dispatchers.IO) {
            runCatching {
                dir.listFiles { f -> EpgCachePolicy.isForProvider(f.name, providerId) }?.forEach { it.delete() }
            }
        }
    }

    private fun fileFor(entry: EntryId) = File(dir, EpgCachePolicy.fileNameFor(entry.providerId, entry.key))

    private fun CachedGuide.toGuide(): EpgGuide {
        val byChannel = programmes.groupBy({ it.c }) {
            EpgProgramme(channelId = it.c, startMs = it.s, stopMs = it.e, title = it.t, description = it.d, category = it.g)
        }
        return EpgGuide(
            channels = channels.map { EpgChannel(id = it.id, displayName = it.n, iconUrl = it.i) },
            programmesByChannel = byChannel,
        )
    }

    private fun EpgGuide.toCached(nowMs: Long, parsedWindow: EpgWindow?): CachedGuide = CachedGuide(
        cachedAtMs = nowMs,
        windowStartMs = parsedWindow?.startMs,
        windowEndMs = parsedWindow?.endMs,
        channels = channels.distinctBy { it.id }.map { CachedChannel(id = it.id, n = it.displayName, i = it.iconUrl) },
        // Providers list some channel ids more than once; write each channel's programmes
        // once, or a reloaded guide shows every programme twice (overlapping cells).
        programmes = channels.distinctBy { it.id }.flatMap { ch ->
            programmesFor(ch.id).map { CachedProgramme(c = it.channelId, s = it.startMs, e = it.stopMs, t = it.title, d = it.description, g = it.category) }
        },
    )
}
