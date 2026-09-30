package com.livewire.tv.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI seam for the self-update feature.
 *
 * Every collaborator ([com.livewire.tv.feature.update.data.GitHubReleaseSource],
 * [com.livewire.tv.feature.update.data.UpdateChecker],
 * [com.livewire.tv.feature.update.data.ApkDownloader],
 * [com.livewire.tv.feature.update.data.UpdateInstaller]) is a `@Singleton` with an
 * `@Inject` constructor whose dependencies (OkHttpClient, Context, SettingsStore) are
 * already provided by [NetworkModule] and Hilt's Android context, so no `@Provides` is
 * needed today. This module exists as the single place to introduce an interface + `@Binds`
 * if the release source is ever swapped (e.g. a non-GitHub source behind a proxy), mirroring
 * how [SportsModule] binds its provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule
