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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private val channelCache: LiveChannelCache,
    private val files: ProviderFileStore,
) {
    /** What this run knows about each provider's pool features; see [poolFeatures]. */
    private val poolStates = ConcurrentHashMap<String, PoolState>()
    /** The `pool_info` check running for each provider, so there is only ever one at a time. */
    private val poolChecks = ConcurrentHashMap<String, Deferred<Unit>>()
    /** Providers whose saved feature list was already looked for this run. */
    private val poolFilesRead: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val poolFileLock = Mutex()
    /** Bumped by [forget] and [forgetProvider], so a check that was running then stores nothing. */
    private val poolClears = AtomicInteger()

    /** Wall clock for the pool-check expiry; tests replace it. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** For calls that must outlive the screen that started them (pool stop, pool checks). */
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

    /** The full channel list if it is already in memory and fresh, without loading it. */
    fun cachedLiveChannels(cfg: ProviderConfig): List<LiveChannel>? = channelCache.cached(cfg)

    /**
     * The full channel list when [categoryId] is null, else one category's channels. The full list
     * comes from [LiveChannelCache]: it can be an older copy, or one saved by the last app run,
     * while a refresh runs in the background ([liveChannelUpdates] delivers the new list).
     */
    suspend fun liveChannels(cfg: ProviderConfig, categoryId: String? = null): List<LiveChannel> =
        if (categoryId == null) channelCache.get(cfg) { fetchLiveChannels(cfg, null) }
        else fetchLiveChannels(cfg, categoryId)

    /** The full channel list, downloaded again unless the in-memory copy is fresh. Empty on failure. */
    suspend fun freshLiveChannels(cfg: ProviderConfig): List<LiveChannel> =
        channelCache.fresh(cfg) { fetchLiveChannels(cfg, null) }

    /** The latest full channel list for [cfg], then each one a refresh brings in. */
    fun liveChannelUpdates(cfg: ProviderConfig): kotlinx.coroutines.flow.Flow<List<LiveChannel>> =
        channelCache.updates(cfg)

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
                        number = entry.number,
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
     * The pool extensions [cfg] supports (see [PoolSignals]). Empty for M3U and plain Xtream
     * panels.
     *
     * Each real `pool_info` answer is saved per provider ([ProviderFileStore]). A launch uses the
     * saved list straight away and asks again in the background, so a slow or busy pool doesn't
     * turn the features off; the new answer replaces it. A real answer is the pool's feature
     * list, or empty for a plain panel; it is then kept for the app run.
     *
     * A timeout, a 429, a server or network error, or a refused login isn't an answer. With a
     * saved list, that list stays in use and the check is tried again after [POOL_RECHECK_MS] (a
     * 429's `Retry-After`, kept between [POOL_RECHECK_MS] and [POOL_RECHECK_MAX_MS]). With
     * nothing saved, the provider counts as plain for that long; a network error isn't kept at
     * all, so the next call asks again. Only one check runs at a time per provider.
     */
    suspend fun poolFeatures(cfg: ProviderConfig): Set<String> {
        if (cfg.type != ProviderType.XTREAM) return emptySet()
        val key = providerKey(cfg)
        val state = poolStates[key] ?: savedPoolState(cfg, key)
        when {
            state == null -> Unit
            state.answered -> return state.features
            state.saved -> {
                if (clock() >= state.retryAt) poolCheck(cfg, key)
                return state.features
            }
            clock() < state.retryAt -> return state.features
        }
        // Nothing saved, and no answer to use: ask and wait, as before saved lists existed.
        poolCheck(cfg, key).await()
        return poolStates[key]?.features.orEmpty()
    }

    /** Waits for a `pool_info` check that is running for [cfg], if any. For tests. */
    internal suspend fun awaitPoolCheck(cfg: ProviderConfig) {
        poolChecks[providerKey(cfg)]?.await()
    }

    /**
     * What this run knows about one provider's features. [answered]: a real answer arrived this
     * run. [saved]: [features] is the list an earlier run saved, in use until an answer comes.
     * Otherwise the last check failed with nothing saved, and [features] (empty) is used until
     * [retryAt]. A saved list is checked again once the clock passes [retryAt].
     */
    private class PoolState(val features: Set<String>, val answered: Boolean, val saved: Boolean, val retryAt: Long)

    /** The saved feature list as this run's state, read once per provider; null when none was saved. */
    private suspend fun savedPoolState(cfg: ProviderConfig, key: String): PoolState? = poolFileLock.withLock {
        poolStates[key]?.let { return@withLock it }
        if (!poolFilesRead.add(key)) return@withLock null
        val saved = files.read(cfg, POOL_FEATURES_FILE, featureListSerializer) ?: return@withLock null
        PoolState(saved.toSet(), answered = false, saved = true, retryAt = 0L).also { poolStates[key] = it }
    }

    /** Starts a `pool_info` check for [cfg], or returns the one already running. */
    private fun poolCheck(cfg: ProviderConfig, key: String): Deferred<Unit> {
        poolChecks[key]?.let { return it }
        val check = background.async(start = CoroutineStart.LAZY) { runPoolCheck(cfg, key) }
        poolChecks.putIfAbsent(key, check)?.let { running ->
            check.cancel()
            return running
        }
        check.invokeOnCompletion { poolChecks.remove(key, check) }
        check.start()
        return check
    }

    private suspend fun runPoolCheck(cfg: ProviderConfig, key: String) {
        val clears = poolClears.get()
        val found = try {
            xtream.poolFeatures(cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (clears != poolClears.get()) return
        val before = poolStates[key]
        when {
            found != null && found.answered -> {
                poolStates[key] = PoolState(found.features, answered = true, saved = false, retryAt = Long.MAX_VALUE)
                files.write(cfg, POOL_FEATURES_FILE, featureListSerializer, found.features.sorted())
            }
            // Not an answer: keep using the saved list, and ask again later.
            before?.saved == true -> poolStates[key] = PoolState(before.features, false, saved = true, retryAt = clock() + retryWait(found))
            found != null -> poolStates[key] = PoolState(emptySet(), false, saved = false, retryAt = clock() + retryWait(found))
            // A network error with nothing saved isn't kept: the next call asks again.
        }
    }

    private fun retryWait(found: XtreamClient.PoolCheck?): Long =
        found?.retryAfterMs?.coerceIn(POOL_RECHECK_MS, POOL_RECHECK_MAX_MS) ?: POOL_RECHECK_MS

    private val featureListSerializer = ListSerializer(String.serializer())

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
        poolClears.incrementAndGet()
        poolStates.clear()
        poolFilesRead.clear()
        m3u.clear()
    }

    /** Drops [providerId]'s saved channel list and pool features, after the provider is edited or deleted. */
    suspend fun forgetProvider(providerId: String) {
        channelCache.clearProvider(providerId)
        poolClears.incrementAndGet()
        val prefix = "$providerId:"
        poolStates.keys.removeAll { it.startsWith(prefix) }
        poolFilesRead.removeAll { it.startsWith(prefix) }
        files.clearProvider(providerId)
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

        /** How long a timed-out `pool_info` check counts as a plain panel before it is tried again. */
        const val POOL_RECHECK_MS = 60_000L

        /** The longest a 429 on `pool_info` counts as a plain panel, whatever its `Retry-After`. */
        const val POOL_RECHECK_MAX_MS = 15 * 60_000L

        /** [ProviderFileStore] kind for the last real `pool_info` feature list. */
        const val POOL_FEATURES_FILE = "pool_features"
    }
}
