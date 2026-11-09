package com.livewire.tv.feature.epg.data

import android.content.Context
import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    // Channel ids the guide was parsed with; null means no filter (all channels kept). A
    // request is only servable from this entry when its channels are a subset of these.
    val channelIds: Set<String>? = null,
    // The guide response's ETag, sent back as If-None-Match when the entry is refreshed.
    val etag: String? = null,
)

/** What a guide download brought back. */
sealed interface GuideDownload {
    /**
     * A parsed guide, with the response's ETag when the server sent one. [window], when set, is
     * the window the guide actually holds, because the server sent less than it was parsed for
     * (a pool's `guide_window`). The cache records it instead of the parsed window, so it never
     * claims programmes it doesn't have.
     */
    data class Loaded(val guide: EpgGuide, val etag: String? = null, val window: EpgWindow? = null) : GuideDownload

    /** HTTP 304: the guide hasn't changed since the ETag that was sent. */
    data object NotModified : GuideDownload
}

/**
 * Caches the parsed EPG guide per provider so the guide opens instantly after the first
 * load. A provider's `xmltv.php` is tens of MB and takes 10-20s to download; it changes
 * only over hours, so a short-TTL cache removes almost all of that cost.
 *
 * Two layers: an in-memory entry (instant, survives while the app lives) and an on-disk
 * JSON entry (survives process death, keyed by a non-secret hash of the provider). A
 * per-key [Mutex] also single-flights concurrent loads: if the guide is opened twice in
 * quick succession, only one download runs and both callers share its result.
 *
 * An entry older than its TTL that still covers the request is returned straight away, and one
 * background refresh runs under the same lock; [updates] tells the screens when it brings a new
 * guide. The refresh sends the entry's ETag as If-None-Match, so an unchanged guide costs one
 * small request and no parse.
 */
@Singleton
class EpgCache internal constructor(
    /** Directory the on-disk JSON entries live in. In the app this is `<cacheDir>/epg_cache`;
     *  tests pass a temp directory so the cache is exercisable without an Android Context. */
    private val dir: File,
    /** Runs background refreshes, which must outlive the screen that started them. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Runs the disk reads and writes. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "epg_cache"))

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Mutex()
    private val locks = HashMap<String, Mutex>()
    // Concurrent: background refreshes for different providers can write at the same time.
    private val memory = ConcurrentHashMap<String, CachedGuide>()

    /** Entries with a background refresh queued or running. */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    /** When an entry's background refresh last failed, so a dead provider isn't retried on every open. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Bumped by [clear] and [clearProvider], so a download that was running then isn't stored. */
    private val clears = AtomicInteger()
    private val versions = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    /**
     * Emits each time a refresh replaces [providerId]'s guide with a changed one, so a screen
     * showing the old guide can fetch again (it is then served from memory).
     */
    fun updates(providerId: String): Flow<Unit> = versionFor(providerId).drop(1).map { }

    private fun versionFor(providerId: String) = versions.getOrPut(providerId) { MutableStateFlow(0) }

    /** Per-key lock, so two providers can load in parallel but one provider single-flights. */
    private suspend fun lockFor(entry: EntryId): Mutex = lock.withLock {
        locks.getOrPut(entry.mapKey) { Mutex() }
    }

    /**
     * Runs [load] under this provider's single-flight lock. If a cached guide exists (memory
     * first, then disk) whose parsed window covers [window] and whose parsed channel set covers
     * [requestedChannelIds], it is returned — filtered to those channels and that window. When
     * that entry is older than [ttlMs] it is still returned at once, and one background refresh
     * calls [load] with the entry's ETag ([serveStale] false waits for that refresh instead).
     * Without such an entry, [load] downloads+parses the guide for [parsedWindow] (over
     * [parsedChannelIds]), the result is stored, then filtered and returned.
     *
     * [load] receives the ETag to send as If-None-Match, or null for a full download. It answers
     * [GuideDownload.NotModified] only when it was given an ETag.
     *
     * Because the key is per provider (not per channel set), Guide and Search share one entry:
     * whichever opens first downloads the superset, and the other is served from it filtered
     * to its own channels. The single-flight lock is likewise per provider, so a concurrent
     * Guide+Search open runs exactly one download.
     *
     * @param requestedChannelIds channels this caller needs; null means every channel. The
     *   returned guide is filtered to these (null returns all cached channels).
     * @param parsedChannelIds channels [load] parses; null means all. Must be a superset of
     *   [requestedChannelIds] (typically null, i.e. all channels) so any caller can be served.
     * @param ttlMs freshness window; a non-positive value makes every entry stale.
     * @param nowMs injectable clock for tests.
     * @param serveStale return a stale entry at once and refresh it in the background. False
     *   (an explicit refresh) waits for the refresh, which still sends the ETag.
     */
    suspend fun getOrLoad(
        key: String,
        providerId: String,
        window: EpgWindow?,
        parsedWindow: EpgWindow?,
        requestedChannelIds: Set<String>? = null,
        parsedChannelIds: Set<String>? = null,
        ttlMs: Long = EpgCachePolicy.DEFAULT_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
        serveStale: Boolean = true,
        load: suspend (ifNoneMatch: String?) -> GuideDownload,
    ): EpgGuide {
        val entry = EntryId(providerId, key)
        return lockFor(entry).withLock {
            val epoch = clears.get()
            val cached = covering(entry, window, requestedChannelIds)
            when {
                cached == null -> {
                    // Nothing usable. No ETag: an unchanged guide still wouldn't cover this request.
                    val loaded = load(null) as? GuideDownload.Loaded ?: error("Guide not modified, but nothing cached")
                    write(entry, loaded, parsedWindow, parsedChannelIds, nowMs, epoch)
                    loaded.guide.filtered(requestedChannelIds, window)
                }
                EpgCachePolicy.isFresh(cached.cachedAtMs, nowMs, ttlMs) -> cached.toGuide().filtered(requestedChannelIds, window)
                serveStale -> {
                    refreshInBackground(entry, parsedWindow, parsedChannelIds, ttlMs, nowMs, load)
                    cached.toGuide().filtered(requestedChannelIds, window)
                }
                else -> when (val result = revalidate(entry, cached, parsedWindow, parsedChannelIds, nowMs, epoch, load)) {
                    is GuideDownload.Loaded -> result.guide.filtered(requestedChannelIds, window)
                    GuideDownload.NotModified -> cached.toGuide().filtered(requestedChannelIds, window)
                }
            }
        }
    }

    /**
     * Refreshes [cached] with its ETag. A 304 marks it fresh again in memory: the disk copy keeps
     * its old time, so the next launch revalidates it once, which is another 304. A new guide
     * replaces it and [updates] fires. Call under the entry's lock.
     */
    private suspend fun revalidate(
        entry: EntryId,
        cached: CachedGuide,
        parsedWindow: EpgWindow?,
        parsedChannelIds: Set<String>?,
        nowMs: Long,
        epoch: Int,
        load: suspend (ifNoneMatch: String?) -> GuideDownload,
    ): GuideDownload {
        val result = load(cached.etag)
        if (epoch != clears.get()) return result
        when (result) {
            GuideDownload.NotModified -> memory[entry.mapKey] = cached.copy(cachedAtMs = nowMs)
            is GuideDownload.Loaded -> {
                write(entry, result, parsedWindow, parsedChannelIds, nowMs, epoch)
                versionFor(entry.providerId).update { it + 1 }
            }
        }
        return result
    }

    private fun refreshInBackground(
        entry: EntryId,
        parsedWindow: EpgWindow?,
        parsedChannelIds: Set<String>?,
        ttlMs: Long,
        nowMs: Long,
        load: suspend (ifNoneMatch: String?) -> GuideDownload,
    ) {
        val mapKey = entry.mapKey
        failedAt[mapKey]?.let { if (nowMs - it in 0 until RETRY_AFTER_MS) return }
        if (!refreshing.add(mapKey)) return
        val epoch = clears.get()
        scope.launch {
            try {
                lockFor(entry).withLock {
                    // Cleared, or refreshed by someone else while this waited.
                    val current = memory[mapKey] ?: return@withLock
                    if (epoch != clears.get() || EpgCachePolicy.isFresh(current.cachedAtMs, nowMs, ttlMs)) return@withLock
                    try {
                        revalidate(entry, current, parsedWindow, parsedChannelIds, nowMs, epoch, load)
                        failedAt.remove(mapKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The stale guide stays up; the next open after RETRY_AFTER_MS tries again.
                        failedAt[mapKey] = nowMs
                    }
                }
            } finally {
                refreshing.remove(mapKey)
            }
        }
    }

    /** Identifies one cache entry: its content [key], namespaced by the [providerId] that owns it. */
    private data class EntryId(val providerId: String, val key: String) {
        /** Stable memory-map / lock key, prefixed so a provider's entries can be matched together. */
        val mapKey: String get() = EpgCachePolicy.providerPrefixFor(providerId) + key
    }

    /** The cached guide for [entry] if it covers [window] and [requested] channels, fresh or
     *  not; null when absent, too narrow a window, or missing requested channels. Memory decides
     *  when it has the entry, since the disk copy holds the same guide. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun covering(
        entry: EntryId,
        window: EpgWindow?,
        requested: Set<String>?,
    ): CachedGuide? {
        fun covers(c: CachedGuide) =
            EpgCachePolicy.covers(c.windowStartMs, c.windowEndMs, window) &&
                EpgCachePolicy.coversChannels(c.channelIds, requested)
        memory[entry.mapKey]?.let { return it.takeIf(::covers) }
        val onDisk = withContext(io) {
            // Decode straight from the file stream rather than reading it into one ~18 MB
            // String first: readText() + decodeFromString would hold the whole JSON text AND
            // the decoded object graph live at the same time, which is a large part of the
            // open-time heap peak. decodeFromStream reads incrementally instead.
            runCatching {
                fileFor(entry).inputStream().buffered().use { json.decodeFromStream<CachedGuide>(it) }
            }.getOrNull()
        } ?: return null
        memory[entry.mapKey] = onDisk
        return onDisk.takeIf(::covers)
    }

    /** Stores [download] in memory and on disk, unless the cache was cleared since [epoch]. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun write(
        entry: EntryId,
        download: GuideDownload.Loaded,
        parsedWindow: EpgWindow?,
        parsedChannelIds: Set<String>?,
        nowMs: Long,
        epoch: Int,
    ) {
        if (epoch != clears.get()) return
        // The window the server actually sent, when it sent less than was parsed for.
        val held = download.window ?: parsedWindow
        val cached = download.guide.toCached(nowMs, held, parsedChannelIds, download.etag)
        memory[entry.mapKey] = cached
        withContext(io) {
            runCatching {
                dir.mkdirs()
                // Encode to the file stream instead of building one large String in memory
                // first: on the measured guide encodeToString would allocate an ~18 MB String
                // on top of the object graph during the write.
                fileFor(entry).outputStream().buffered().use { json.encodeToStream(cached, it) }
            }
        }
    }

    /** Drops all cached guides, for example after a provider is deleted. */
    suspend fun clear() = lock.withLock {
        clears.incrementAndGet()
        memory.clear()
        failedAt.clear()
        locks.clear()
        withContext(io) { runCatching { dir.deleteRecursively() } }
    }

    /**
     * Drops only [providerId]'s cached guides, in memory and on disk, leaving every other
     * provider's entries intact. Call when a provider is deleted, or edited in a way that
     * changes its guide (credentials/endpoint) so its old entries are unreachable anyway.
     * Old-format files without a provider prefix are left untouched.
     */
    suspend fun clearProvider(providerId: String) = lock.withLock {
        val prefix = EpgCachePolicy.providerPrefixFor(providerId)
        clears.incrementAndGet()
        memory.keys.removeAll { it.startsWith(prefix) }
        failedAt.keys.removeAll { it.startsWith(prefix) }
        // Locks stay: dropping one while a load holds it would let the next open start a
        // second, concurrent download for the same entry.
        withContext(io) {
            runCatching {
                dir.listFiles { f -> EpgCachePolicy.isForProvider(f.name, providerId) }?.forEach { it.delete() }
            }
        }
    }

    private fun fileFor(entry: EntryId) = File(dir, EpgCachePolicy.fileNameFor(entry.providerId, entry.key))

    private fun CachedGuide.toGuide(): EpgGuide {
        // Intern while rebuilding domain objects: the decoded CachedProgramme list holds a
        // fresh String per field per element (one channel id repeated on every programme, a
        // synopsis repeated on every airing), so collapsing equal strings to one instance
        // here removes the same ~18 MB of duplicate retained text the parser interning does.
        val interner = StringInterner()
        val byChannel = programmes.groupBy({ interner.internNonNull(it.c) }) {
            EpgProgramme(
                channelId = interner.internNonNull(it.c),
                startMs = it.s,
                stopMs = it.e,
                title = interner.internNonNull(it.t),
                description = interner.intern(it.d),
                category = interner.intern(it.g),
            )
        }
        return EpgGuide(
            channels = channels.map {
                EpgChannel(id = interner.internNonNull(it.id), displayName = interner.internNonNull(it.n), iconUrl = it.i)
            },
            programmesByChannel = byChannel,
        )
    }

    private fun EpgGuide.toCached(nowMs: Long, parsedWindow: EpgWindow?, parsedChannelIds: Set<String>?, etag: String?): CachedGuide = CachedGuide(
        cachedAtMs = nowMs,
        windowStartMs = parsedWindow?.startMs,
        windowEndMs = parsedWindow?.endMs,
        channelIds = parsedChannelIds,
        etag = etag,
        channels = channels.distinctBy { it.id }.map { CachedChannel(id = it.id, n = it.displayName, i = it.iconUrl) },
        // Providers list some channel ids more than once; write each channel's programmes
        // once, or a reloaded guide shows every programme twice (overlapping cells).
        programmes = channels.distinctBy { it.id }.flatMap { ch ->
            programmesFor(ch.id).map { CachedProgramme(c = it.channelId, s = it.startMs, e = it.stopMs, t = it.title, d = it.description, g = it.category) }
        },
    )

    /**
     * A view of this guide restricted to [channelIds] (null keeps all) and to [window] (null
     * keeps all programmes). The cache stores a superset — all channels, parsed over a window
     * widened by the TTL — so each caller filters it down to exactly the channels and window
     * it asked for. Filtering here (not while parsing) is what lets one entry serve Guide and
     * Search alike without either seeing the other's extra channels or wider window.
     */
    private fun EpgGuide.filtered(channelIds: Set<String>?, window: EpgWindow?): EpgGuide {
        if (channelIds == null && window == null) return this
        val keptChannels = channels.filter { channelIds == null || it.id in channelIds }
        val byChannel = keptChannels.associate { ch ->
            val programmes = programmesFor(ch.id).let { list ->
                if (window == null) list else list.filter { window.overlaps(it) }
            }
            ch.id to programmes
        }
        return EpgGuide(channels = keptChannels, programmesByChannel = byChannel)
    }

    internal companion object {
        /** How long after a failed background refresh the next open may try again. */
        const val RETRY_AFTER_MS = 60_000L
    }
}
