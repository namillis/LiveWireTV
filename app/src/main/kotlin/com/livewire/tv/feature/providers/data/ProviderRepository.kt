package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one entry point screens use for provider data, whatever the provider type.
 * Xtream calls go to [XtreamClient]; M3U playlists go to [M3uClient]. Screens never
 * branch on [ProviderType] themselves.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val xtream: XtreamClient,
    private val m3u: M3uClient,
) {

    /** Checks that the provider is reachable and usable before it is saved. */
    suspend fun validate(cfg: ProviderConfig): ProviderAuthResult = when (cfg.type) {
        ProviderType.XTREAM -> xtream.authenticate(cfg)
        ProviderType.M3U -> try {
            val loaded = m3u.load(cfg, forceRefresh = true)
            if (loaded.entriesById.isEmpty()) {
                ProviderAuthResult(ok = false, message = "The playlist has no live channels.")
            } else {
                ProviderAuthResult(ok = true)
            }
        } catch (_: Exception) {
            ProviderAuthResult(
                ok = false,
                message = "Could not load the playlist. Check the URL and network.",
            )
        }
    }

    suspend fun liveCategories(cfg: ProviderConfig): List<ProviderCategory> = when (cfg.type) {
        ProviderType.XTREAM -> xtream.liveCategories(cfg)
        ProviderType.M3U -> m3u.load(cfg).entriesById.values
            .map { it.groupName() }
            .distinct()
            .map { ProviderCategory(id = it, name = it) }
    }

    suspend fun liveChannels(cfg: ProviderConfig, categoryId: String? = null): List<LiveChannel> =
        when (cfg.type) {
            ProviderType.XTREAM -> xtream.liveChannels(cfg, categoryId)
            ProviderType.M3U -> m3u.load(cfg).entriesById
                .filterValues { categoryId == null || it.groupName() == categoryId }
                .map { (id, entry) ->
                    LiveChannel(
                        streamId = id,
                        name = entry.name,
                        logoUrl = entry.logoUrl,
                        epgChannelId = entry.tvgId,
                        categoryId = entry.groupName(),
                    )
                }
        }

    /** Resolves the playable source. Returns null when the channel no longer exists. */
    suspend fun playbackSource(cfg: ProviderConfig, streamId: String, xtreamExt: String): PlaybackSource? =
        when (cfg.type) {
            ProviderType.XTREAM -> PlaybackSource(cfg.liveStreamUrl(streamId, xtreamExt))
            ProviderType.M3U -> m3u.load(cfg).entriesById[streamId]?.let {
                PlaybackSource(url = it.url, headers = it.headers)
            }
        }

    /** XMLTV guide URL, or null when this provider has no guide configured. */
    suspend fun guideUrl(cfg: ProviderConfig): String? = when (cfg.type) {
        ProviderType.XTREAM ->
            "${cfg.baseUrl}/xmltv.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}"
        ProviderType.M3U -> cfg.epgUrl?.trim()?.ifEmpty { null }
            ?: runCatching { m3u.load(cfg).playlist.epgUrl }.getOrNull()
    }

    suspend fun forget() = m3u.clear()

    private fun M3uEntry.groupName(): String = group ?: UNCATEGORIZED

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val UNCATEGORIZED = "Uncategorized"
    }
}
