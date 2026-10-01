package com.livewire.tv.di

import android.content.Context
import com.livewire.tv.feature.favorites.data.FavoritesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the per-provider favourites store ([FavoritesStore]). A single app-wide instance
 * so every screen that reads or toggles favourites (Home, Guide, Player, Search, the
 * management screen) and the Providers screen that clears them on delete share one store.
 */
@Module
@InstallIn(SingletonComponent::class)
object FavoritesModule {

    @Provides
    @Singleton
    fun provideFavoritesStore(
        @ApplicationContext context: Context,
    ): FavoritesStore = FavoritesStore(context)
}
