package com.livewire.tv.feature.providers.domain

/** A channel's category and the channels in it, found from the provider's full channel list. */
data class CategoryMatch(val categoryId: String, val channels: List<LiveChannel>)

object CategoryLookup {
    /**
     * The category [streamId] belongs to and that category's channels in list order, read from
     * [all] (the provider's full channel list) with no further requests. Null when the stream
     * isn't in the list or carries no category, so the caller can fall back to asking each
     * category in turn.
     */
    fun find(all: List<LiveChannel>, streamId: String): CategoryMatch? {
        val hit = all.firstOrNull { it.streamId == streamId } ?: return null
        if (hit.categoryId.isBlank()) return null
        return CategoryMatch(hit.categoryId, all.filter { it.categoryId == hit.categoryId })
    }
}
