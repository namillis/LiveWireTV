package com.livewire.tv.di

import com.livewire.tv.feature.sports.data.EspnSportsProvider
import com.livewire.tv.feature.sports.domain.SportsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the sports source. Swap the bound impl to change providers app-wide. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SportsModule {
    @Binds
    @Singleton
    abstract fun bindSportsProvider(impl: EspnSportsProvider): SportsProvider
}
