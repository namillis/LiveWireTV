package com.livewire.tv.feature.epg

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory

/** A category the Guide can be narrowed to, with how many of the loaded channels it holds. */
data class GuideCategory(val id: String, val name: String, val channelCount: Int)

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
 * The rows left after the category and keyword filters. [categoryId] null means all
 * channels. The keyword matches anywhere in the channel name, ignoring case and surrounding
 * spaces; a blank keyword matches everything.
 */
internal fun filterGuideRows(rows: List<GuideRow>, categoryId: String?, query: String): List<GuideRow> {
    val q = query.trim()
    if (categoryId == null && q.isEmpty()) return rows
    return rows.filter { row ->
        (categoryId == null || row.channel.categoryId == categoryId) &&
            (q.isEmpty() || row.channel.name.contains(q, ignoreCase = true))
    }
}
