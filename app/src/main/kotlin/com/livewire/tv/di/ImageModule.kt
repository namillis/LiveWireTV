package com.livewire.tv.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Coil image loader with EXPLICITLY BOUNDED caches — lean discipline #1 from the ADR.
 * Channel-logo grids can otherwise balloon the image cache on a low-RAM TV box, so we
 * cap memory at a small share of the heap and the disk cache at a fixed size, and
 * reuse the app's single OkHttpClient rather than letting Coil spin up its own.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15) // ≤15% of the app heap for decoded logos
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024) // 64 MB on disk, hard cap
                    .build()
            }
            .okHttpClient(okHttpClient)
            .build()
}
