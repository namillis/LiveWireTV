package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.catchup.domain.ArchiveUrlBuilder
import com.livewire.tv.feature.providers.domain.ChannelNames
import com.livewire.tv.feature.providers.domain.CatchupSpec
import com.livewire.tv.feature.providers.domain.CatchupType
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
    private val channelCache = LiveChannelCache()
    private val poolFeatures = ConcurrentHashMap<String, Set<String>>()

    /** For fire-and-forget calls that must outlive the screen that started them (pool stop). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        if (categoryId == null) channelCache.get(cfg) { fetchLiveChannels(cfg, null) }
        else fetchLiveChannels(cfg, categoryId)

    private suspend fun fetchLiveChannels(cfg: ProviderConfig, categoryId: String?): List<LiveChannel> =
        // Providers pad their live lists with category-separator rows
        // ("##### US - NEWS #####", "===== SPORTS ====="). Drop them at this one
        // choke point so no screen (Home rails/hero, Guide, Sports picker, Search)
        // ever sees a placeholder as a channel.
        when (cfg.type) {
            // The pool check runs alongside the download, so it adds no wait, and is cached after
            // its first answer. Events are dropped unless the pool lists event_info.
            ProviderType.XTREAM -> coroutineScope {
                val features = async { poolFeatures(cfg) }
                PoolSignals.withEvents(xtream.liveChannels(cfg, categoryId), features.await())
            }
            ProviderType.M3U -> m3u.load(cfg).entriesById
                .filterValues { categoryId == null || it.groupName() == categoryId }
                .map { (id, entry) ->
                    val spec = entry.toCatchupSpec()
                    LiveChannel(
                        streamId = id,
                        name = entry.name,
                        logoUrl = entry.logoUrl,
                        epgChannelId = entry.tvgId,
                        categoryId = entry.groupName(),
                        archiveDays = spec?.days ?: 0,
                        catchup = spec,
                    )
                }
        }.filterNot { ChannelNames.isPlaceholder(it.name) }

    /** Resolves the playable source. Returns null when the channel no longer exists. */
    suspend fun playbackSource(
        cfg: ProviderConfig,
        streamId: String,
        xtreamExt: String,
        /** A [com.livewire.tv.feature.providers.domain.PoolSource.key] the user picked, for a pool provider. */
        poolSource: String? = null,
    ): PlaybackSource? =
        when (cfg.type) {
            ProviderType.XTREAM -> withPoolSignals(cfg, streamId, cfg.liveStreamUrl(streamId, xtreamExt), poolSource)
            ProviderType.M3U -> m3u.load(cfg).entriesById[streamId]?.let {
                PlaybackSource(url = it.url, headers = it.headers)
            }
        }

    /** The pool's streams for [streamId], or empty for a provider that isn't the pool. */
    suspend fun poolSources(cfg: ProviderConfig, streamId: String): List<com.livewire.tv.feature.providers.domain.PoolSource> =
        if (PoolSignals.SOURCES in poolFeatures(cfg)) xtream.poolSources(cfg, streamId) else emptyList()

    /**
     * The pool extensions [cfg] supports, checked once per app run (see [PoolSignals]). Empty for
     * M3U and plain Xtream panels. A failed check is retried on the next call instead of cached.
     */
    suspend fun poolFeatures(cfg: ProviderConfig): Set<String> {
        if (cfg.type != ProviderType.XTREAM) return emptySet()
        val key = providerKey(cfg)
        poolFeatures[key]?.let { return it }
        val found = xtream.poolFeatures(cfg) ?: return emptySet()
        poolFeatures[key] = found
        return found
    }

    private suspend fun withPoolSignals(cfg: ProviderConfig, streamId: String, url: String, poolSource: String?): PlaybackSource {
        val features = poolFeatures(cfg)
        if (features.isEmpty()) return PlaybackSource(url)
        val retry = PoolSignals.RETRY in features
        val opening = when {
            poolSource != null && PoolSignals.SOURCES in features -> PoolSignals.pickedUrl(url, poolSource)
            retry -> PoolSignals.attemptUrl(url, 0)
            else -> url
        }
        return PlaybackSource(
            url = opening,
            retryUrl = if (retry) { attempt, refused -> PoolSignals.attemptUrl(url, attempt, refused) } else null,
            onStop = if (PoolSignals.STOP in features) stopper(cfg, streamId) else null,
        )
    }

    private fun stopper(cfg: ProviderConfig, streamId: String): () -> Unit = {
        background.launch { xtream.poolStop(cfg, streamId) }
    }

    private fun providerKey(cfg: ProviderConfig): String =
        listOf(cfg.id, cfg.baseUrl, cfg.username, cfg.password).joinToString("\u0000").hashCode().let { "${cfg.id}:$it" }

    /**
     * Resolve an ARCHIVE (catch-up) source for [channel] starting at [startMs] (UTC) for
     * [durationMin] minutes, or null when the channel has no archive, the window is out of
     * range, or the recipe can't be resolved. The URL is security-sensitive; keep it out of
     * routes/logs/errors. For Xtream this uses the in-memory `server_info` for the zone; for
     * M3U it passes the channel's resolved live URL + headers to the builder.
     */
    suspend fun archiveSource(
        cfg: ProviderConfig,
        channel: LiveChannel,
        startMs: Long,
        durationMin: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): PlaybackSource? = when (cfg.type) {
        ProviderType.XTREAM -> ArchiveUrlBuilder.archiveUrl(
            cfg = cfg,
            channel = channel,
            startMs = startMs,
            durationMin = durationMin,
            // authenticate() only runs when a provider is saved, so after an app restart the
            // server's timezone is unknown. Fetch it once on first archive use; guessing UTC
            // would shift every archive start by the server's offset.
            serverInfo = xtream.serverInfo(cfg.id)
                ?: runCatching { xtream.authenticate(cfg) }.getOrNull()?.let { xtream.serverInfo(cfg.id) },
            nowMs = nowMs,
        )?.let { src ->
            if (PoolSignals.STOP in poolFeatures(cfg)) src.copy(onStop = stopper(cfg, channel.streamId)) else src
        }
        ProviderType.M3U -> {
            val entry = m3u.load(cfg).entriesById[channel.streamId]
            ArchiveUrlBuilder.archiveUrl(
                cfg = cfg,
                channel = channel,
                startMs = startMs,
                durationMin = durationMin,
                nowMs = nowMs,
                m3uLiveUrl = entry?.url,
                m3uHeaders = entry?.headers ?: emptyMap(),
            )
        }
    }

    /** Fallback Xtream archive URL (timeshift.php) for a 404 on the primary path form. */
    fun archiveFallbackUrl(cfg: ProviderConfig, channel: LiveChannel, startMs: Long, durationMin: Int): String? =
        ArchiveUrlBuilder.xtreamFallbackUrl(cfg, channel, startMs, durationMin, xtream.serverInfo(cfg.id))

    /**
     * The provider's `server_info` (timezone + clock) for building Xtream archive start times.
     * Cached from the last authenticate; fetched once here when missing (after a restart the
     * zone is unknown and guessing UTC would shift every archive start). Null for M3U.
     */
    suspend fun serverInfo(cfg: ProviderConfig): com.livewire.tv.feature.providers.domain.ServerInfo? {
        if (cfg.type != ProviderType.XTREAM) return null
        return xtream.serverInfo(cfg.id)
            ?: runCatching { xtream.authenticate(cfg) }.getOrNull()?.let { xtream.serverInfo(cfg.id) }
    }

    /**
     * The resolved live URL + per-channel HTTP headers for an M3U [streamId], as the pair the
     * archive URL builder needs ([com.livewire.tv.feature.catchup.domain.ArchiveUrlBuilder]).
     * Security-sensitive (the URL embeds credentials). Null for Xtream or an unknown id.
     */
    suspend fun m3uEntry(cfg: ProviderConfig, streamId: String): Pair<String, Map<String, String>>? {
        if (cfg.type != ProviderType.M3U) return null
        val entry = m3u.load(cfg).entriesById[streamId] ?: return null
        return entry.url to entry.headers
    }

    /** XMLTV guide URL, or null when this provider has no guide configured. */
    suspend fun guideUrl(cfg: ProviderConfig): String? = when (cfg.type) {
        ProviderType.XTREAM ->
            "${cfg.baseUrl}/xmltv.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}"
        ProviderType.M3U -> cfg.epgUrl?.trim()?.ifEmpty { null }
            ?: runCatching { m3u.load(cfg).playlist.epgUrl }.getOrNull()
    }

    suspend fun forget() {
        channelCache.clear()
        poolFeatures.clear()
        m3u.clear()
    }

    private fun M3uEntry.groupName(): String = group ?: UNCATEGORIZED

    /**
     * Translate an M3U entry's raw catch-up attributes into a [CatchupSpec], or null when
     * the channel advertises no catch-up. An unrecognised type token yields null (fail soft:
     * a channel we can't build an archive URL for is treated as having none). When a type is
     * present but `catchup-days` is missing/zero, default to 7 days so the window is usable.
     */
    private fun M3uEntry.toCatchupSpec(): CatchupSpec? {
        val type = when (catchupType?.trim()?.lowercase()) {
            "default", "" -> CatchupType.DEFAULT
            "append" -> CatchupType.APPEND
            "shift", "timeshift" -> CatchupType.SHIFT
            "flussonic", "fs" -> CatchupType.FLUSSONIC
            "xc" -> CatchupType.XC
            null -> return null
            else -> return null
        }
        val days = if (catchupDays > 0) catchupDays else 7
        return CatchupSpec(type = type, source = catchupSource, days = days)
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val UNCATEGORIZED = "Uncategorized"
    }
}
