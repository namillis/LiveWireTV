package com.livewire.tv.feature.favorites.data

import android.content.Context
import com.livewire.tv.feature.providers.domain.LiveChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One favourited channel, scoped to the provider it came from (the file is keyed by
 * `providerId`, so the provider is not repeated on the entry).
 *
 * [streamId] is the primary key within a provider, but for M3U it is a hash of the stream
 * URL ([com.livewire.tv.feature.providers.data.M3uClient.streamIdFor]) and changes if the
 * provider rotates the URL. [name] and [epgChannelId] are stored so an entry can be
 * re-matched by name after such a change; see [FavoritesLogic.resolve].
 *
 * Serialized to JSON on disk, so never rename or drop existing fields; add new ones with
 * defaults. [addedAt] is only used to break ties and is not shown; [position] is the
 * authoritative display order.
 */
@Serializable
data class FavoriteEntry(
    val streamId: String,
    val name: String,
    val epgChannelId: String? = null,
    val addedAt: Long = 0L,
    val position: Int = 0,
)

/** A favourite paired with its current live channel, or flagged when the channel is gone. */
data class ResolvedFavorite(
    val entry: FavoriteEntry,
    /** The current [LiveChannel] this favourite maps to, or null when [unavailable]. */
    val channel: LiveChannel?,
    /** True when no current channel matches by stream id or by name in this provider. */
    val unavailable: Boolean,
)

/**
 * Pure list and resolver operations for favourites. Kept separate from the on-disk store so
 * ordering, dedupe, reordering, and the re-match rule are unit-testable without Android.
 *
 * Entries for a provider are always held position-first; every transform renumbers
 * [FavoriteEntry.position] to a dense `0..n-1` sequence so the stored order is unambiguous.
 */
object FavoritesLogic {

    /** Sort by [FavoriteEntry.position], breaking ties by [FavoriteEntry.addedAt] then id. */
    fun ordered(entries: List<FavoriteEntry>): List<FavoriteEntry> =
        entries.sortedWith(compareBy({ it.position }, { it.addedAt }, { it.streamId }))

    /** Renumber [entries] to dense `0..n-1` positions in their current order. */
    private fun renumber(entries: List<FavoriteEntry>): List<FavoriteEntry> =
        entries.mapIndexed { index, entry -> if (entry.position == index) entry else entry.copy(position = index) }

    /**
     * Add [entry] to [current] at the end, or move it there and refresh its display fields if
     * a favourite with the same [FavoriteEntry.streamId] already exists (idempotent add).
     */
    fun add(current: List<FavoriteEntry>, entry: FavoriteEntry): List<FavoriteEntry> {
        val ordered = ordered(current)
        val existing = ordered.firstOrNull { it.streamId == entry.streamId }
        if (existing != null) {
            // Keep its place; only refresh the display fields that may have changed.
            return renumber(
                ordered.map {
                    if (it.streamId == entry.streamId) {
                        it.copy(name = entry.name, epgChannelId = entry.epgChannelId)
                    } else {
                        it
                    }
                },
            )
        }
        return renumber(ordered + entry.copy(position = ordered.size))
    }

    /** Remove the favourite with [streamId], if present, and close the gap in positions. */
    fun remove(current: List<FavoriteEntry>, streamId: String): List<FavoriteEntry> =
        renumber(ordered(current).filterNot { it.streamId == streamId })

    /** Remove [streamId] if present, otherwise add [entryIfMissing]. */
    fun toggle(current: List<FavoriteEntry>, streamId: String, entryIfMissing: FavoriteEntry): List<FavoriteEntry> =
        if (ordered(current).any { it.streamId == streamId }) {
            remove(current, streamId)
        } else {
            add(current, entryIfMissing)
        }

    /** Move [streamId] up one place (toward position 0). No-op if absent or already first. */
    fun moveUp(current: List<FavoriteEntry>, streamId: String): List<FavoriteEntry> {
        val ordered = ordered(current).toMutableList()
        val index = ordered.indexOfFirst { it.streamId == streamId }
        if (index <= 0) return renumber(ordered)
        ordered.add(index - 1, ordered.removeAt(index))
        return renumber(ordered)
    }

    /** Move [streamId] down one place (toward the end). No-op if absent or already last. */
    fun moveDown(current: List<FavoriteEntry>, streamId: String): List<FavoriteEntry> {
        val ordered = ordered(current).toMutableList()
        val index = ordered.indexOfFirst { it.streamId == streamId }
        if (index < 0 || index >= ordered.size - 1) return renumber(ordered)
        ordered.add(index + 1, ordered.removeAt(index))
        return renumber(ordered)
    }

    /** Move [streamId] to [targetIndex] (clamped to the valid range). No-op if absent. */
    fun moveTo(current: List<FavoriteEntry>, streamId: String, targetIndex: Int): List<FavoriteEntry> {
        val ordered = ordered(current).toMutableList()
        val index = ordered.indexOfFirst { it.streamId == streamId }
        if (index < 0) return renumber(ordered)
        val clamped = targetIndex.coerceIn(0, ordered.size - 1)
        if (clamped == index) return renumber(ordered)
        ordered.add(clamped, ordered.removeAt(index))
        return renumber(ordered)
    }

    /**
     * Write back the stream ids [resolve] rewrote on a name-fallback match. Entries are paired
     * by [FavoriteEntry.position] (dense and unique after every transform) plus [name], so a
     * list that changed since [resolved] was built is never mis-patched. A new id that is
     * already used by another entry is skipped rather than creating a duplicate.
     */
    fun applyRematches(current: List<FavoriteEntry>, resolved: List<ResolvedFavorite>): List<FavoriteEntry> {
        val rewrites = resolved
            .filter { !it.unavailable }
            .associate { it.entry.position to it.entry }
        val used = current.mapTo(HashSet()) { it.streamId }
        return ordered(current).map { entry ->
            val patched = rewrites[entry.position]
            if (patched == null || patched.name != entry.name || patched.streamId == entry.streamId ||
                patched.streamId in used
            ) {
                entry
            } else {
                used.add(patched.streamId)
                entry.copy(streamId = patched.streamId)
            }
        }
    }

    /**
     * Map each favourite (in stored order) to the current live channel it names:
     *  1. by [FavoriteEntry.streamId] — the fast, exact path;
     *  2. else by an exact [LiveChannel.name] match within this provider — recovers an entry
     *     whose M3U stream id changed on a refresh, and rewrites the stored [streamId] to the
     *     new one so later lookups take the fast path;
     *  3. else mark it [ResolvedFavorite.unavailable] (channel dropped from the provider).
     *
     * A live channel is claimed by at most one favourite, so two favourites cannot both
     * fall back onto the same renamed channel.
     */
    fun resolve(entries: List<FavoriteEntry>, channels: List<LiveChannel>): List<ResolvedFavorite> {
        val byId = channels.associateBy { it.streamId }
        // First non-favourited channel per name, for the fallback; a name may repeat.
        val byName = LinkedHashMap<String, LiveChannel>()
        for (channel in channels) byName.putIfAbsent(channel.name, channel)

        val claimed = HashSet<String>()
        return ordered(entries).map { entry ->
            val byStreamId = byId[entry.streamId]
            if (byStreamId != null && claimed.add(byStreamId.streamId)) {
                return@map ResolvedFavorite(entry, byStreamId, unavailable = false)
            }
            val byExactName = byName[entry.name]
            if (byExactName != null && byExactName.streamId !in claimed && claimed.add(byExactName.streamId)) {
                // Rewrite the stored id to the channel's current one so step 1 hits next time.
                return@map ResolvedFavorite(
                    entry.copy(streamId = byExactName.streamId),
                    byExactName,
                    unavailable = false,
                )
            }
            ResolvedFavorite(entry, channel = null, unavailable = true)
        }
    }
}

/**
 * Persists favourites per provider as JSON on disk. Mirrors the DataStore-style JSON
 * serialization of [com.livewire.tv.feature.search.data.RecentHistoryStore], but is backed by
 * a plain [File] directory (one file per provider) so it is exercisable in unit tests without
 * an Android `Context`, the same pattern [com.livewire.tv.feature.epg.data.EpgCache] uses.
 *
 * All list and resolver transforms delegate to [FavoritesLogic]; this class only reads,
 * writes, (de)serializes, and publishes reactive views. Favourites are not secret, and the
 * app sets `allowBackup=false` globally (see the manifest), so — like [RecentHistoryStore] and
 * [EpgCache] — no per-store backup exclusion is needed. Provided by
 * [com.livewire.tv.di.FavoritesModule].
 */
@Singleton
class FavoritesStore internal constructor(
    /** Directory the per-provider JSON files live in. In the app this is
     *  `<filesDir>/favorites`; tests pass a temp directory. */
    private val dir: File,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "favorites"))

    private val json = Json { ignoreUnknownKeys = true }

    // One writer lock across all providers keeps a read-modify-write atomic; a per-provider
    // in-memory cache backs the reactive Flows and avoids re-reading the file on every emit.
    private val mutex = Mutex()
    // Concurrent: favorites()/favoriteIds() are called from the main thread while update()
    // runs under the mutex on another dispatcher.
    private val flows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<FavoriteEntry>>>()

    /** Entries for [providerId], ordered by position, updated whenever they change. */
    fun favorites(providerId: String): Flow<List<FavoriteEntry>> = flowFor(providerId).asStateFlow()

    /** The set of favourited stream ids for [providerId], for cheap `isFavorite` checks in UI. */
    fun favoriteIds(providerId: String): Flow<Set<String>> =
        favorites(providerId).map { entries -> entries.map { it.streamId }.toSet() }

    /** A one-shot check; prefer the [favoriteIds] Flow for anything reactive. */
    suspend fun isFavorite(providerId: String, streamId: String): Boolean =
        flowFor(providerId).value.any { it.streamId == streamId }

    /** Add [channel] to [providerId]'s favourites (idempotent), stamping [now] as its add time. */
    suspend fun add(providerId: String, channel: LiveChannel, now: Long = System.currentTimeMillis()) =
        update(providerId) { current ->
            FavoritesLogic.add(
                current,
                FavoriteEntry(
                    streamId = channel.streamId,
                    name = channel.name,
                    epgChannelId = channel.epgChannelId,
                    addedAt = now,
                    position = current.size,
                ),
            )
        }

    /** Remove [streamId] from [providerId]'s favourites, if present. */
    suspend fun remove(providerId: String, streamId: String) =
        update(providerId) { FavoritesLogic.remove(it, streamId) }

    /** Toggle [channel]'s favourite state for [providerId]. */
    suspend fun toggle(providerId: String, channel: LiveChannel, now: Long = System.currentTimeMillis()) =
        update(providerId) { current ->
            FavoritesLogic.toggle(
                current,
                channel.streamId,
                FavoriteEntry(
                    streamId = channel.streamId,
                    name = channel.name,
                    epgChannelId = channel.epgChannelId,
                    addedAt = now,
                    position = current.size,
                ),
            )
        }

    /** Move [streamId] up one place in [providerId]'s list. */
    suspend fun moveUp(providerId: String, streamId: String) =
        update(providerId) { FavoritesLogic.moveUp(it, streamId) }

    /** Move [streamId] down one place in [providerId]'s list. */
    suspend fun moveDown(providerId: String, streamId: String) =
        update(providerId) { FavoritesLogic.moveDown(it, streamId) }

    /** Move [streamId] to [targetIndex] in [providerId]'s list. */
    suspend fun moveTo(providerId: String, streamId: String, targetIndex: Int) =
        update(providerId) { FavoritesLogic.moveTo(it, streamId, targetIndex) }

    /**
     * Persist the stream-id rewrites that [FavoritesLogic.resolve] made on a name-fallback
     * match, in place (position and add time kept). Call with the resolver's output after
     * building a rail or filter so the fast id path hits next time and [favoriteIds] carries
     * the channel's current id. Entries whose id did not change are left alone; a rewrite that
     * would collide with an id already in the list is skipped.
     */
    suspend fun applyRematches(providerId: String, resolved: List<ResolvedFavorite>) =
        update(providerId) { current ->
            FavoritesLogic.applyRematches(current, resolved)
        }

    /**
     * Delete every favourite for [providerId] (memory + disk). Call when the provider is
     * deleted; hooked next to the guide-cache delete in
     * [com.livewire.tv.feature.providers.ProvidersViewModel.remove].
     */
    suspend fun removeProvider(providerId: String) = mutex.withLock {
        flows[providerId]?.value = emptyList()
        withContext(Dispatchers.IO) { runCatching { fileFor(providerId).delete() } }
    }

    /** Read-modify-write [providerId]'s entries atomically and publish the result. */
    private suspend fun update(providerId: String, transform: (List<FavoriteEntry>) -> List<FavoriteEntry>) =
        mutex.withLock {
            val flow = flowFor(providerId)
            val next = transform(flow.value)
            if (next == flow.value) return@withLock
            flow.value = next
            writeToDisk(providerId, next)
        }

    /** The cached Flow for [providerId], loading its file from disk on first access. */
    private fun flowFor(providerId: String): MutableStateFlow<List<FavoriteEntry>> =
        flows.getOrPut(providerId) { MutableStateFlow(readFromDisk(providerId)) }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readFromDisk(providerId: String): List<FavoriteEntry> =
        runCatching {
            fileFor(providerId).inputStream().buffered().use {
                FavoritesLogic.ordered(json.decodeFromStream<List<FavoriteEntry>>(it))
            }
        }.getOrDefault(emptyList())

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeToDisk(providerId: String, entries: List<FavoriteEntry>) =
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                fileFor(providerId).outputStream().buffered().use { json.encodeToStream(entries, it) }
            }
            Unit
        }

    /** Per-provider file name. The provider id is hashed so an id with path characters (or a
     *  secret-looking id) never becomes a file path or leaks into the filesystem. */
    private fun fileFor(providerId: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
        val name = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(dir, "fav_$name.json")
    }
}
