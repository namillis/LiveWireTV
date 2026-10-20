package com.livewire.tv.feature.home

/**
 * Pure rules for Home's single On now list and its filter dropdown. No Android, so every case
 * runs on the JVM.
 *
 * The dropdown holds, in order: the pool's Live Now category (when the provider sends one),
 * Favourites, Recently watched, the pinned (or first) categories, and All channels. Empty lists
 * are left out.
 */
object HomeLists {
    /** The category name the IPTV pool gives its live events list. Other providers never send it. */
    const val LIVE_NOW_NAME = "Live Now"

    const val FAVORITES_ID = "\u0000favorites"
    const val RECENT_ID = "\u0000recent"
    const val ALL_ID = "\u0000all"

    fun isLiveNow(categoryName: String): Boolean = categoryName.trim().equals(LIVE_NOW_NAME, ignoreCase = true)

    /** The dropdown's lists in order. [categories] may include the Live Now one; it is not repeated. */
    fun lists(
        liveNow: ChannelRail?,
        favorites: ChannelRail?,
        recent: ChannelRail?,
        categories: List<ChannelRail>,
        all: ChannelRail?,
    ): List<ChannelRail> {
        val live = liveNow?.takeIf { it.channels.isNotEmpty() }
        val rest = categories.filter { it.channels.isNotEmpty() && it.categoryId != live?.categoryId && !isLiveNow(it.title) }
        return listOfNotNull(live, favorites, recent) + rest + listOfNotNull(all?.takeIf { it.channels.isNotEmpty() })
    }

    /**
     * Which list Home opens on: the one picked last time on this provider ([remembered]) while it
     * still exists, else Live Now, else Favourites, else Recently watched, else All channels,
     * else the first list. Null when there are no lists.
     */
    fun defaultSelection(lists: List<ChannelRail>, remembered: String?): String? {
        val ids = lists.map { it.categoryId }
        if (remembered != null && remembered in ids) return remembered
        lists.firstOrNull { it.isLiveNow }?.let { return it.categoryId }
        for (id in listOf(FAVORITES_ID, RECENT_ID, ALL_ID)) if (id in ids) return id
        return ids.firstOrNull()
    }

    /**
     * The number shown on a row: the provider's channel number, else the row's position in the
     * list (1-based), since the pool and many M3U lists send no numbers.
     */
    fun rowNumber(providerNumber: Int?, index: Int): String = (providerNumber ?: (index + 1)).toString()

    /** "1,204" for the dropdown's counts. */
    fun count(n: Int): String = "%,d".format(java.util.Locale.US, n)
}
