package com.livewire.tv.feature.epg.data

import android.content.Context
import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
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
    private suspend fun lockFor(key: String): Mutex = lock.withLock {
        locks.getOrPut(key) { Mutex() }
    }

    /**
     * Runs [load] under this provider's single-flight lock. If a fresh cached guide exists
     * (memory first, then disk) it is returned without calling [load] at all. Otherwise
     * [load] downloads+parses the guide, and the result is stored in both layers.
     *
     * @param ttlMs freshness window; a non-positive value disables the cache (always loads).
     * @param nowMs injectable clock for tests.
     */
    suspend fun getOrLoad(
        key: String,
        ttlMs: Long = EpgCachePolicy.DEFAULT_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
        load: suspend () -> EpgGuide,
    ): EpgGuide = lockFor(key).withLock {
        read(key, nowMs, ttlMs)?.let { return@withLock it }
        val guide = load()
        write(key, guide, nowMs)
        guide
    }

    /** Fresh cached guide for [key], or null when absent or stale. */
    private suspend fun read(key: String, nowMs: Long, ttlMs: Long): EpgGuide? {
        memory[key]?.let { if (EpgCachePolicy.isFresh(it.cachedAtMs, nowMs, ttlMs)) return it.toGuide() }
        val onDisk = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<CachedGuide>(fileFor(key).readText()) }.getOrNull()
        } ?: return null
        if (!EpgCachePolicy.isFresh(onDisk.cachedAtMs, nowMs, ttlMs)) return null
        memory[key] = onDisk
        return onDisk.toGuide()
    }

    private suspend fun write(key: String, guide: EpgGuide, nowMs: Long) {
        val cached = guide.toCached(nowMs)
        memory[key] = cached
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                fileFor(key).writeText(json.encodeToString<CachedGuide>(cached))
            }
        }
    }

    /** Drops all cached guides, for example after a provider is deleted. */
    suspend fun clear() = lock.withLock {
        memory.clear()
        locks.clear()
        withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
    }

    private fun fileFor(key: String) = File(dir, "$key.json")

    private fun CachedGuide.toGuide(): EpgGuide {
        val byChannel = programmes.groupBy({ it.c }) {
            EpgProgramme(channelId = it.c, startMs = it.s, stopMs = it.e, title = it.t, description = it.d, category = it.g)
        }
        return EpgGuide(
            channels = channels.map { EpgChannel(id = it.id, displayName = it.n, iconUrl = it.i) },
            programmesByChannel = byChannel,
        )
    }

    private fun EpgGuide.toCached(nowMs: Long): CachedGuide = CachedGuide(
        cachedAtMs = nowMs,
        channels = channels.map { CachedChannel(id = it.id, n = it.displayName, i = it.iconUrl) },
        programmes = channels.flatMap { ch ->
            programmesFor(ch.id).map { CachedProgramme(c = it.channelId, s = it.startMs, e = it.stopMs, t = it.title, d = it.description, g = it.category) }
        },
    )
}
