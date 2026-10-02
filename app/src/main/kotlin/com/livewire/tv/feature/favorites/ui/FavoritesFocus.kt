package com.livewire.tv.feature.favorites.ui

/**
 * Pure focus-restore logic shared by screens that open the hold-OK menu / Channel info panel
 * over a grid of channel cards (Home, Search). When an overlay closes, focus must return to the
 * exact item that opened it — but toggling a favourite adds or removes the Favourites rail, so
 * the item's rail/index can shift. The opener is therefore tracked by channel stream id, and
 * this picker resolves that id back to a position in the CURRENT layout.
 *
 * [rails] is the current layout as a list of rails, each a list of channel stream ids, in
 * display order. [favoritesRailIndex] is the index of the Favourites rail in [rails], or -1 when
 * there is none. The result is the (railIndex, cardIndex) to focus, or null when there is
 * nothing focusable.
 */
object FavoritesFocus {

    data class Target(val railIndex: Int, val cardIndex: Int)

    /**
     * Pick where focus should land for [streamId] after an overlay closes:
     *  1. the item in a NON-favourites (category) rail — the stable home for the channel, so
     *     focus does not jump onto the Favourites rail that a toggle just added;
     *  2. else the item wherever it is (e.g. only present in the Favourites rail);
     *  3. else the first card of the first non-empty rail (the item was removed entirely).
     * Returns null only when there is no focusable card at all.
     */
    fun restoreTarget(
        rails: List<List<String>>,
        favoritesRailIndex: Int,
        streamId: String?,
    ): Target? {
        if (streamId != null) {
            // 1. exact id in a category rail.
            rails.forEachIndexed { railIndex, ids ->
                if (railIndex != favoritesRailIndex) {
                    val cardIndex = ids.indexOf(streamId)
                    if (cardIndex >= 0) return Target(railIndex, cardIndex)
                }
            }
            // 2. exact id anywhere (only in the Favourites rail).
            rails.forEachIndexed { railIndex, ids ->
                val cardIndex = ids.indexOf(streamId)
                if (cardIndex >= 0) return Target(railIndex, cardIndex)
            }
        }
        // 3. first card of the first non-empty rail.
        val firstRail = rails.indexOfFirst { it.isNotEmpty() }
        return if (firstRail >= 0) Target(firstRail, 0) else null
    }
}
