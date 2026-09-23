package com.livewire.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * LiveWire application entry point. Hilt's dependency graph is rooted here, and the
 * app supplies Coil's app-wide ImageLoader (the bounded one from ImageModule) so every
 * AsyncImage uses the capped caches — lean discipline #1.
 */
@HiltAndroidApp
class LiveWireApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(): ImageLoader = imageLoader
}
