package com.livewire.tv.feature.home.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.search.data.WatchedChannel

/** Pure rules for which rails Home shows. No Android, so every case runs on the JVM. */
object HomeRails {
    /** Category rails Home shows, and the most categories that can be pinned. */
    const val MAX_CATEGORY_RAILS = 6

    /** The outcome of a pin toggle: the new list, and whether the category is now pinned. */
    data class PinResult(val pinned: List<String>, val isPinned: Boolean, val full: Boolean = false)

    /**
     * Pin [categoryId] at the end of [current], or unpin it if it's already there. Pinning when
     * [MAX_CATEGORY_RAILS] are already pinned changes nothing and reports [PinResult.full].
     */
    fun togglePin(current: List<String>, categoryId: String): PinResult = when {
        categoryId in current -> PinResult(current - categoryId, isPinned = false)
        current.size >= MAX_CATEGORY_RAILS -> PinResult(current, isPinned = false, full = true)
        else -> PinResult(current + categoryId, isPinned = true)
    }

    /**
     * The categories to show as rails: the pinned ones still offered by the provider, in pin
     * order. With none pinned (or none of the pins still existing), the provider's first
     * [MAX_CATEGORY_RAILS], which is what Home always showed.
     */
    fun categoryRails(categories: List<ProviderCategory>, pinned: List<String>): List<ProviderCategory> {
        val byId = categories.associateBy { it.id }
        val chosen = pinned.mapNotNull { byId[it] }.distinctBy { it.id }
        return chosen.ifEmpty { categories.take(MAX_CATEGORY_RAILS) }
    }

    /**
     * Recently watched channels for [providerId], most recent first, resolved against the
     * provider's current [channels]: by stream id, else by exact name for a stream whose id
     * changed. Entries no longer in the list are left out.
     */
    fun recentlyWatched(watched: List<WatchedChannel>, providerId: String, channels: List<LiveChannel>): List<LiveChannel> {
        val byId = channels.associateBy { it.streamId }
        val byName = HashMap<String, LiveChannel>()
        for (c in channels) byName.putIfAbsent(c.name, c)
        return watched
            .filter { it.providerId == providerId }
            .mapNotNull { byId[it.streamId] ?: byName[it.name] }
            .distinctBy { it.streamId }
    }

    /** A remembered card: which rail it was on and which channel, stable when rails move. */
    fun focusMemory(railId: String, streamId: String): String = "$railId\u0001$streamId"

    /**
     * The `railIndex:cardIndex` position for the remembered card [memory] in [rails] (each rail
     * as its id and its stream ids). Rails come and go (Favourites, Recently watched, pins), so
     * a position saved earlier can point at the wrong card or none. Resolution order: the same
     * channel on the same rail, else the same channel on any rail, else the first card of the
     * first non-empty rail. Null when there are no cards at all.
     */
    fun focusPosition(rails: List<Pair<String, List<String>>>, memory: String?): String? {
        val parts = memory?.split('\u0001')
        if (parts != null && parts.size == 2) {
            val (railId, streamId) = parts
            rails.forEachIndexed { r, (id, ids) ->
                if (id == railId) ids.indexOf(streamId).takeIf { it >= 0 }?.let { return "$r:$it" }
            }
            rails.forEachIndexed { r, (_, ids) ->
                ids.indexOf(streamId).takeIf { it >= 0 }?.let { return "$r:$it" }
            }
        }
        val first = rails.indexOfFirst { it.second.isNotEmpty() }
        return if (first >= 0) "$first:0" else null
    }
}
