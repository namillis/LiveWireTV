package com.livewire.tv.di

import android.content.Context
import com.livewire.tv.feature.search.data.RecentHistoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Search empty-state history store ([RecentHistoryStore]), which persists recent
 * searches and recently watched channels to DataStore. A single app-wide instance so every
 * screen that records (Search, Player) and the Search screen that reads share one store.
 */
@Module
@InstallIn(SingletonComponent::class)
object SearchHistoryModule {

    @Provides
    @Singleton
    fun provideRecentHistoryStore(
        @ApplicationContext context: Context,
    ): RecentHistoryStore = RecentHistoryStore(context)
}
