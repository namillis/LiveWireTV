package com.livewire.tv.feature.favorites.ui

import com.livewire.tv.feature.favorites.data.FavoriteEntry
import com.livewire.tv.feature.providers.domain.LiveChannel

/**
 * Pure helpers shared by every favorites entry point (Home, Guide, Search, Player). Kept out
 * of the Compose files so the add-time stamping and the menu's label logic are unit-testable
 * without an emulator.
 */
object FavoritesUi {

    /**
     * Build the [FavoriteEntry] to store for [channel]. [position] is left at 0 and [addedAt]
     * at [now]; the store's [com.livewire.tv.feature.favorites.data.FavoritesLogic.add]
     * renumbers the position when it appends, so only the display fields and the add time
     * matter here. The channel's current [LiveChannel.streamId] / [LiveChannel.name] /
     * [LiveChannel.epgChannelId] are copied so a later M3U id change can be re-matched by name.
     */
    fun entryFor(channel: LiveChannel, now: Long = System.currentTimeMillis()): FavoriteEntry =
        FavoriteEntry(
            streamId = channel.streamId,
            name = channel.name,
            epgChannelId = channel.epgChannelId,
            addedAt = now,
        )

    /** The Favorite row's label: toggles between adding and removing (mockup option2-home). */
    fun favoriteActionLabel(isFavorite: Boolean): String =
        if (isFavorite) "Remove from Favorites" else "Add to Favorites"

    /** The brief add/remove confirmation text, top-right (mockup toast). */
    fun confirmationLabel(nowFavorite: Boolean): String =
        if (nowFavorite) "Added to Favorites" else "Removed from Favorites"
}

/**
 * The three rows of the hold-OK channel menu (mockup option2-home). [FAVORITE] is focused by
 * default; its label flips to "Remove from Favorites" when the channel is already a favorite.
 * [CHANNEL_INFO]'s panel is not designed yet, so its callback only closes the menu for now.
 */
enum class ChannelMenuItem { FAVORITE, PLAY, CHANNEL_INFO }
