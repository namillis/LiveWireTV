package com.livewire.tv.feature.epg

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory

/** A category the Guide can be narrowed to, with how many of the loaded channels it holds. */
data class GuideCategory(val id: String, val name: String, val channelCount: Int)

/** The sentinel category id for the ★ Favourites filter, shown first in the column (task §4). */
const val FAVORITES_CATEGORY_ID = "\u0000favorites"

/**
 * Categories worth offering in the Guide's category column: the provider's own order, each
 * with its channel count, dropping categories with no loaded channels (a provider's header-only
 * categories, or ones whose channels were all placeholders).
 */
internal fun guideCategories(categories: List<ProviderCategory>, channels: List<LiveChannel>): List<GuideCategory> {
    val counts = channels.groupingBy { it.categoryId }.eachCount()
    return categories
        .distinctBy { it.id }
        .mapNotNull { c -> counts[c.id]?.let { GuideCategory(c.id, c.name, it) } }
}

/**
 * The ★ Favourites entry for the top of the category column. Its count is the number of
 * favourite rows the grid will actually show — the resolved, available favourites among the
 * loaded [rows] — so the count equals the rows displayed (task §4: don't show 5 for 4).
 */
internal fun favoritesCategory(rows: List<GuideRow>, favoriteIds: Set<String>): GuideCategory =
    GuideCategory(FAVORITES_CATEGORY_ID, "Favourites", rows.count { it.channel.streamId in favoriteIds })

/**
 * The rows left after the category and keyword filters. [categoryId] null means all
 * channels; [FAVORITES_CATEGORY_ID] keeps only rows whose channel is in [favoriteIds]. The
 * keyword matches anywhere in the channel name, ignoring case and surrounding spaces; a blank
 * keyword matches everything.
 */
internal fun filterGuideRows(
    rows: List<GuideRow>,
    categoryId: String?,
    query: String,
    favoriteIds: Set<String> = emptySet(),
): List<GuideRow> {
    val q = query.trim()
    if (categoryId == null && q.isEmpty()) return rows
    return rows.filter { row ->
        val categoryOk = when (categoryId) {
            null -> true
            FAVORITES_CATEGORY_ID -> row.channel.streamId in favoriteIds
            else -> row.channel.categoryId == categoryId
        }
        categoryOk && (q.isEmpty() || row.channel.name.contains(q, ignoreCase = true))
    }
}
