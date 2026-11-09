package com.livewire.tv.feature.providers.data

import android.content.Context
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** On-disk shape of one channel. Short names keep a 23,000-channel file to a few MB. */
@Serializable
private data class StoredChannel(
    /** [LiveChannel.streamId] */ val i: String,
    /** [LiveChannel.name] */ val n: String,
    /** [LiveChannel.logoUrl] */ val l: String? = null,
    /** [LiveChannel.epgChannelId] */ val e: String? = null,
    /** [LiveChannel.categoryId] */ val c: String,
    /** [LiveChannel.archiveDays] */ val a: Int = 0,
    /** [LiveChannel.number] */ val u: Int? = null,
)

@Serializable
private data class StoredList(val v: Int, val savedAtMs: Long, val channels: List<StoredChannel>)

/**
 * Each provider's full live channel list, shared by Home, the Guide, Search, Sports and the player
 * so they don't each download it.
 *
 * In memory, a list counts as fresh for [ttlMs]. A fresh list is returned as it is. An older one
 * is still returned straight away, and one background refresh starts (tried again a few times if
 * it fails); [updates] delivers the new list when it lands. Only a provider with no list at all
 * makes the caller wait for the download.
 *
 * Xtream lists are also written to disk, so the next app launch has a list without waiting for the
 * network. A list read back from disk is shown first and always refreshed once, whatever its age:
 * it has no pool events ([LiveChannel.poolEvent] is never written) and may be days old. M3U lists
 * stay in memory only, because their catch-up templates can embed credentials and the player needs
 * the playlist loaded anyway.
 *
 * Entries are keyed by the provider's id and connection details, so editing a provider's URL or
 * login starts a fresh entry. File names are one-way hashes, never the URL or login. Concurrent
 * callers for the same provider share one load. An empty result is never cached or written,
 * because the Xtream client returns empty on an HTTP error.
 */
@Singleton
class LiveChannelCache internal constructor(
    /** Where the on-disk copies live; null keeps everything in memory. */
    private val dir: File?,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Runs background refreshes and disk writes, which must outlive the screen that asked. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "channel_cache"))

    /** [fromDisk] marks a list read back from an earlier run: shown, but never fresh. */
    private class Entry(val at: Long, val channels: List<LiveChannel>, val fromDisk: Boolean = false)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val latest = ConcurrentHashMap<String, MutableStateFlow<List<LiveChannel>?>>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    /** When a background refresh last failed, so an unreachable provider isn't retried on every call. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Bumped by [clear] and [clearProvider], so a load that was running then doesn't store its result. */
    private val clears = AtomicInteger()
    private val diskLock = Mutex()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * [cfg]'s full list: the in-memory copy if there is one, else the disk copy, else [load]. A copy
     * that isn't fresh is refreshed in the background; see the class docs.
     */
    suspend fun get(cfg: ProviderConfig, load: suspend () -> List<LiveChannel>): List<LiveChannel> {
        val key = keyFor(cfg)
        entries[key]?.let { return served(cfg, key, it, load) }
        return lockFor(key).withLock {
            entries[key]?.let { return served(cfg, key, it, load) }
            val epoch = clears.get()
            readDisk(cfg)?.let { fromDisk ->
                if (epoch == clears.get()) {
                    entries[key] = fromDisk
                    publish(key, fromDisk.channels)
                }
                return served(cfg, key, fromDisk, load)
            }
            load().also { store(cfg, key, it, epoch) }
        }
    }

    /**
     * [cfg]'s list no older than the TTL: the in-memory copy while it is fresh, else a new [load].
     * For a caller that is already refreshing in the background and wants the new list itself.
     * Empty when the load fails.
     */
    suspend fun fresh(cfg: ProviderConfig, load: suspend () -> List<LiveChannel>): List<LiveChannel> {
        val key = keyFor(cfg)
        entries[key]?.takeIf(::isFresh)?.let { return it.channels }
        return lockFor(key).withLock {
            entries[key]?.takeIf(::isFresh)?.let { return it.channels }
            val epoch = clears.get()
            load().also { store(cfg, key, it, epoch) }
        }
    }

    /** [cfg]'s list if it is in memory and fresh; never loads. A list read from disk doesn't count. */
    fun cached(cfg: ProviderConfig): List<LiveChannel>? = entries[keyFor(cfg)]?.takeIf(::isFresh)?.channels

    /**
     * The latest list held for [cfg], then each new one stored after a refresh. Starts with the
     * current list, so a screen that subscribes late still sees a refresh that already landed.
     */
    fun updates(cfg: ProviderConfig): Flow<List<LiveChannel>> = flowFor(keyFor(cfg)).filterNotNull()

    /** Drops every in-memory list. Disk copies stay; [clearProvider] removes one provider's. */
    fun clear() {
        clears.incrementAndGet()
        entries.clear()
        failedAt.clear()
        latest.values.forEach { it.value = null }
    }

    /** Drops [providerId]'s lists from memory and disk, after the provider is edited or deleted. */
    suspend fun clearProvider(providerId: String) {
        clears.incrementAndGet()
        val prefix = "$providerId:"
        entries.keys.removeAll { it.startsWith(prefix) }
        failedAt.keys.removeAll { it.startsWith(prefix) }
        latest.forEach { (key, flow) -> if (key.startsWith(prefix)) flow.value = null }
        val dir = dir ?: return
        diskLock.withLock {
            withContext(io) {
                runCatching { dir.listFiles { f -> f.name.startsWith(filePrefixFor(providerId)) }?.forEach { it.delete() } }
            }
        }
    }

    private fun served(cfg: ProviderConfig, key: String, entry: Entry, load: suspend () -> List<LiveChannel>): List<LiveChannel> {
        if (!isFresh(entry)) refreshInBackground(cfg, key, load)
        return entry.channels
    }

    private fun refreshInBackground(cfg: ProviderConfig, key: String, load: suspend () -> List<LiveChannel>) {
        failedAt[key]?.let { if (clock() - it in 0 until RETRY_AFTER_MS) return }
        if (!refreshing.add(key)) return
        val epoch = clears.get()
        scope.launch {
            try {
                // A failed refresh is tried again a few times, because no screen may ask again
                // soon and the old list (a disk copy has no pool events) would stay up meanwhile.
                var retries = 0
                while (!refreshOnce(cfg, key, load, epoch) && retries++ < MAX_RETRIES) delay(RETRY_AFTER_MS)
            } finally {
                refreshing.remove(key)
            }
        }
    }

    /** One background refresh attempt. False when the load failed and the old list is still up. */
    private suspend fun refreshOnce(cfg: ProviderConfig, key: String, load: suspend () -> List<LiveChannel>, epoch: Int): Boolean =
        lockFor(key).withLock {
            // Someone else refreshed it while this waited, or it was cleared.
            val current = entries[key] ?: return@withLock true
            if (isFresh(current) || epoch != clears.get()) return@withLock true
            val loaded = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (loaded.isEmpty()) {
                failedAt[key] = clock()
                false
            } else {
                store(cfg, key, loaded, epoch)
                true
            }
        }

    private fun store(cfg: ProviderConfig, key: String, channels: List<LiveChannel>, epoch: Int) {
        if (channels.isEmpty() || epoch != clears.get()) return
        val entry = Entry(clock(), channels)
        entries[key] = entry
        failedAt.remove(key)
        publish(key, channels)
        if (persists(cfg)) scope.launch { writeDisk(cfg, key, entry) }
    }

    private fun publish(key: String, channels: List<LiveChannel>) {
        flowFor(key).value = channels
    }

    private fun flowFor(key: String) = latest.getOrPut(key) { MutableStateFlow(null) }

    private fun lockFor(key: String) = locks.getOrPut(key) { Mutex() }

    private fun isFresh(entry: Entry): Boolean = !entry.fromDisk && clock() - entry.at in 0 until ttlMs

    private fun persists(cfg: ProviderConfig) = dir != null && cfg.type == ProviderType.XTREAM

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readDisk(cfg: ProviderConfig): Entry? {
        if (!persists(cfg)) return null
        val file = fileFor(cfg) ?: return null
        return withContext(io) {
            runCatching {
                if (!file.isFile) return@runCatching null
                val stored = file.inputStream().buffered().use { json.decodeFromStream<StoredList>(it) }
                if (stored.v != FORMAT_VERSION || stored.channels.isEmpty()) return@runCatching null
                // A provider has a few hundred categories across tens of thousands of channels.
                val categories = HashMap<String, String>()
                Entry(stored.savedAtMs, stored.channels.map { it.toChannel(categories) }, fromDisk = true)
            }.getOrNull()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeDisk(cfg: ProviderConfig, key: String, entry: Entry) = diskLock.withLock {
        // A newer list replaced this one, or the provider was cleared, while this waited.
        if (entries[key] !== entry) return@withLock
        val dir = dir ?: return@withLock
        val target = fileFor(cfg) ?: return@withLock
        withContext(io) {
            runCatching {
                dir.mkdirs()
                // Write beside the target and rename, so a crash mid-write never leaves half a file.
                val tmp = File(dir, "${target.name}.tmp")
                tmp.outputStream().buffered().use { out ->
                    json.encodeToStream(StoredList(FORMAT_VERSION, entry.at, entry.channels.map { it.toStored() }), out)
                }
                // Files left by this provider's earlier login or URL can't be reached any more.
                dir.listFiles { f -> f.name.startsWith(filePrefixFor(cfg.id)) && f != tmp && f != target }
                    ?.forEach { it.delete() }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        }
    }

    private fun fileFor(cfg: ProviderConfig): File? =
        dir?.let { File(it, "${filePrefixFor(cfg.id)}${sha256Hex(connection(cfg), bytes = 16)}.json") }

    private fun keyFor(cfg: ProviderConfig): String = "${cfg.id}:${connection(cfg).hashCode()}"

    private fun connection(cfg: ProviderConfig): String =
        listOf(cfg.id, cfg.type.name, cfg.baseUrl, cfg.username, cfg.password).joinToString("\u0000")

    private fun LiveChannel.toStored() = StoredChannel(
        i = streamId, n = name, l = logoUrl, e = epgChannelId, c = categoryId, a = archiveDays, u = number,
    )

    private fun StoredChannel.toChannel(categories: HashMap<String, String>) = LiveChannel(
        streamId = i, name = n, logoUrl = l, epgChannelId = e, categoryId = categories.getOrPut(c) { c },
        archiveDays = a, number = u,
    )

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** How long to wait after a failed background refresh before trying again. */
        internal const val RETRY_AFTER_MS = 60 * 1000L

        /** Retries after a failed background refresh; later calls to [get] can start a new one. */
        internal const val MAX_RETRIES = 4

        /** Bump when [StoredChannel] changes shape; older files are then ignored and rewritten. */
        private const val FORMAT_VERSION = 1

        /** `<hash of the provider id>_`, shared by every file of one provider. */
        internal fun filePrefixFor(providerId: String) = "${sha256Hex(providerId, bytes = 8)}_"

        private fun sha256Hex(material: String, bytes: Int): String =
            MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
                .take(bytes).joinToString("") { "%02x".format(it) }
    }
}
