package com.livewire.tv.feature.search.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * A channel the user has watched, remembered for the Search "Jump back in" rail. Only the
 * non-secret reference ([providerId] + [streamId]) plus display fields are stored; the logo
 * and "what's on now" are re-resolved at display time against the live channel list, so a
 * stale entry still renders and a channel dropped from the playlist simply falls away.
 *
 * Serialized to DataStore, so never rename or drop existing fields; add new ones with
 * defaults. [ts] is only used to order most-recent-first and is not shown.
 */
@Serializable
data class WatchedChannel(
    val providerId: String,
    val streamId: String,
    val name: String,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val categoryId: String = "",
    val ts: Long = 0L,
)

/** The persisted empty-state history: recent search queries and recently watched channels. */
data class RecentHistory(
    val searches: List<String> = emptyList(),
    val watched: List<WatchedChannel> = emptyList(),
)

/**
 * Pure list operations for the Search empty-state history. Kept separate from the DataStore
 * so dedupe, ordering, caps, and the minimum-length rule are unit-testable without Android.
 *
 * Both lists are most-recent-first: a repeat moves to the front rather than adding a
 * duplicate, and the list is capped to a fixed size (oldest entries fall off the end).
 */
object RecentHistoryLogic {
    /** Shortest query worth remembering. Single characters are noise, not a search. */
    const val MIN_QUERY_LENGTH = 2
    const val MAX_SEARCHES = 6
    const val MAX_WATCHED = 8

    /**
     * Prepend [raw] to [current], deduping case-insensitively (most recent wins, keeping the
     * new casing) and capping at [MAX_SEARCHES]. Queries are trimmed; anything shorter than
     * [MIN_QUERY_LENGTH] after trimming is rejected and [current] is returned unchanged.
     */
    fun addSearch(current: List<String>, raw: String): List<String> {
        val query = raw.trim()
        if (query.length < MIN_QUERY_LENGTH) return current
        val deduped = current.filterNot { it.equals(query, ignoreCase = true) }
        return (listOf(query) + deduped).take(MAX_SEARCHES)
    }

    /**
     * Prepend [channel] to [current], deduping by (providerId, streamId) — most recent wins,
     * carrying the new entry's display fields — and capping at [MAX_WATCHED].
     */
    fun addWatched(current: List<WatchedChannel>, channel: WatchedChannel): List<WatchedChannel> {
        val deduped = current.filterNot {
            it.providerId == channel.providerId && it.streamId == channel.streamId
        }
        return (listOf(channel) + deduped).take(MAX_WATCHED)
    }
}

private val Context.recentDataStore by preferencesDataStore(name = "livewire_recent")

/**
 * Persists the Search empty-state history (recent searches, recently watched channels) with
 * the app's DataStore, mirroring [com.livewire.tv.feature.settings.data.SettingsStore]. All
 * list transforms delegate to [RecentHistoryLogic]; this class only reads, writes, and
 * (de)serializes. Provided by [com.livewire.tv.di.SearchHistoryModule].
 */
@Singleton
class RecentHistoryStore(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SEARCHES = stringPreferencesKey("recentSearches")
        val WATCHED = stringPreferencesKey("watchedChannels")
    }

    val history: Flow<RecentHistory> = context.recentDataStore.data.map { p ->
        RecentHistory(
            searches = p[Keys.SEARCHES]?.let(::decodeSearches).orEmpty(),
            watched = p[Keys.WATCHED]?.let(::decodeWatched).orEmpty(),
        )
    }

    /** Record a search query, if it passes the minimum-length rule. */
    suspend fun recordSearch(query: String) {
        context.recentDataStore.edit { p ->
            val current = p[Keys.SEARCHES]?.let(::decodeSearches).orEmpty()
            val next = RecentHistoryLogic.addSearch(current, query)
            if (next != current) p[Keys.SEARCHES] = encodeSearches(next)
        }
    }

    /** Record a watched channel (by provider + stream id), most recent first. */
    suspend fun recordWatched(channel: WatchedChannel) {
        context.recentDataStore.edit { p ->
            val current = p[Keys.WATCHED]?.let(::decodeWatched).orEmpty()
            val next = RecentHistoryLogic.addWatched(current, channel)
            p[Keys.WATCHED] = encodeWatched(next)
        }
    }

    /** Clear all remembered history (both lists). Backs the empty-state "Clear" affordance. */
    suspend fun clear() {
        context.recentDataStore.edit { p ->
            p.remove(Keys.SEARCHES)
            p.remove(Keys.WATCHED)
        }
    }

    private fun decodeSearches(raw: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())

    private fun encodeSearches(list: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), list)

    private fun decodeWatched(raw: String): List<WatchedChannel> =
        runCatching { json.decodeFromString(ListSerializer(WatchedChannel.serializer()), raw) }.getOrDefault(emptyList())

    private fun encodeWatched(list: List<WatchedChannel>): String =
        json.encodeToString(ListSerializer(WatchedChannel.serializer()), list)
}
