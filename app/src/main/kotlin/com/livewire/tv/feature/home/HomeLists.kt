package com.livewire.tv.feature.home

/**
 * Pure rules for Home's single On now list and its filter dropdown. No Android, so every case
 * runs on the JVM.
 *
 * The dropdown holds, in order: the pool's live sports category (when the provider sends one),
 * Favorites, Recently watched, the pinned (or first) categories, and All channels. Empty lists
 * are left out.
 */
object HomeLists {
    /** The name the IPTV pool gave its live sports events list until 2026-11-06. Other providers never send it. */
    const val LIVE_NOW_NAME = "Live Now"

    /** The pool's name for that list since then (same category id). Other panels use it too. */
    const val LIVE_SPORTS_NAME = "Live Sports"

    const val FAVORITES_ID = "\u0000favorites"
    const val RECENT_ID = "\u0000recent"
    const val ALL_ID = "\u0000all"

    /**
     * True for the pool's live sports events list. "Live Now" is only ever the pool's. "Live
     * Sports" is a common category name on plain panels too, so it counts only when [pool] (the
     * provider lists the pool's `event_info` feature).
     */
    fun isLiveNow(categoryName: String, pool: Boolean = false): Boolean {
        val name = categoryName.trim()
        return name.equals(LIVE_NOW_NAME, ignoreCase = true) || (pool && name.equals(LIVE_SPORTS_NAME, ignoreCase = true))
    }

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
     * still exists, else Live Now, else Favorites, else Recently watched, else All channels,
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

    /** Home's live list (null when there is none) and its category rails. */
    data class Rails(val liveNow: ChannelRail?, val categories: List<ChannelRail>)

    /**
     * Home's rails built from the provider's full channel list, for when the per-category
     * requests are slow: each category's channels, in the list's order. The pool sends each
     * category's channels in the full list in the same order as its own list. [pool] as for
     * [isLiveNow]. Empty categories are left out, as when they're loaded one by one.
     */
    fun railsFromChannels(
        categories: List<com.livewire.tv.feature.providers.domain.ProviderCategory>,
        pinned: List<String>,
        channels: List<com.livewire.tv.feature.providers.domain.LiveChannel>,
        pool: Boolean,
    ): Rails {
        val byCategory = channels.groupBy { it.categoryId }
        fun rail(c: com.livewire.tv.feature.providers.domain.ProviderCategory) =
            byCategory[c.id]?.let { ChannelRail(c.id, c.name, it) }
        val live = categories.firstOrNull { isLiveNow(it.name, pool) }?.let(::rail)?.copy(isLiveNow = true)
        val rails = com.livewire.tv.feature.home.data.HomeRails.categoryRails(categories, pinned).mapNotNull(::rail)
        return Rails(live, rails)
    }
}
