package com.livewire.tv.feature.epg.data

import com.livewire.tv.core.net.decodedStream
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the provider's XMLTV guide (Xtream `xmltv.php`, or an M3U playlist's guide
 * URL) and parses it directly from the response stream. [window] bounds retained
 * programme data during parsing, which keeps large guides viable on low-memory TVs.
 *
 * The parsed guide is cached per provider by [EpgCache]: provider guides are tens of MB
 * and take 10-20s to download but change only over hours, so every open after the first
 * is served from cache instead of re-downloading. The cache also single-flights concurrent
 * opens so the guide is never downloaded twice at once.
 *
 * A pool that lists `guide_window` ([PoolSignals.GUIDE_WINDOW]) is asked for only the window
 * the cache keeps, instead of its full 6 hours back and 36 ahead.
 */
@Singleton
class EpgRepository @Inject constructor(
    private val http: OkHttpClient,
    private val providers: ProviderRepository,
    private val cache: EpgCache,
) {
    /** Wall clock for working out the pool's guide window; tests replace it. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Fetch the guide and return it filtered to [channelIds] (all channels when null) and to
     * [window]. Returns a cached guide when one covers the request, even past its TTL: a stale
     * one is then refreshed in the background and [updates] fires if the guide changed.
     * Otherwise downloads and parses the provider's full guide once, caches it per provider,
     * and filters the result. Throws on network/parse failure or when the provider has no
     * guide (and nothing is cached).
     *
     * The cache holds ONE superset entry per provider (all channels, a widened window), so
     * every screen shares it: Guide and Search no longer download the guide once each.
     *
     * @param forceRefresh waits for a refresh instead of serving the cached guide (used by an
     *   explicit refresh). An unchanged guide is answered with a 304 and kept.
     */
    suspend fun fetch(
        cfg: ProviderConfig,
        window: EpgWindow? = null,
        channelIds: Set<String>? = null,
        forceRefresh: Boolean = false,
    ): EpgGuide {
        val url = providers.guideUrl(cfg) ?: error("No guide configured for this provider")
        val key = EpgCachePolicy.keyFor(cfg, url)
        // A forced refresh treats the cached guide as stale and waits for the refresh. It still
        // sends the ETag, so an unchanged guide comes back as a 304 without a download.
        val ttl = if (forceRefresh) 0L else EpgCachePolicy.DEFAULT_TTL_MS
        // Parse a superset window (widened by the TTL and by the lead/trail that spans both the
        // Guide's and Search's windows), so a differently-windowed sibling screen — and the same
        // screen reopened later — is served from this one entry instead of re-downloading.
        val parsedWindow = EpgCachePolicy.downloadWindow(window)
        // Parse ALL channels (no filter), so the single per-provider entry is a superset every
        // caller can be served from; each caller's [channelIds] filter is applied on read. This
        // is why opening Search after the Guide (or vice versa) reuses the cached ~64 MB guide.
        return cache.getOrLoad(
            key = key,
            providerId = cfg.id,
            window = window,
            parsedWindow = parsedWindow,
            requestedChannelIds = channelIds,
            parsedChannelIds = null,
            ttlMs = ttl,
            serveStale = !forceRefresh,
        ) { ifNoneMatch ->
            download(cfg, url, parsedWindow, ifNoneMatch)
        }
    }

    /**
     * Emits when a refresh brought [cfg] a changed guide. A screen that showed the older one
     * calls [fetch] again and is served the new guide from memory.
     */
    fun updates(cfg: ProviderConfig): Flow<Unit> = cache.updates(cfg.id)

    /**
     * Downloads the guide for [window]. A pool that lists `guide_window` is asked for that window
     * only; everything else gets the full guide, as before.
     */
    private suspend fun download(
        cfg: ProviderConfig,
        url: String,
        window: EpgWindow?,
        ifNoneMatch: String?,
    ): GuideDownload {
        val pool = window?.takeIf { PoolSignals.GUIDE_WINDOW in providers.poolFeatures(cfg) }
            ?.let { EpgCachePolicy.poolGuideRequest(it, clock()) }
        return withContext(Dispatchers.IO) {
            downloadGuide(http, url, window, channelIds = null, ifNoneMatch, pool, clock)
        }
    }

    internal companion object {
        private const val HTTP_NOT_MODIFIED = 304

        /**
         * Downloads and parses the guide at [url]. With [ifNoneMatch] (the cached guide's ETag)
         * it is sent as If-None-Match, and a 304 comes back as [GuideDownload.NotModified] with
         * nothing downloaded or parsed.
         *
         * With [pool], `past_hours` and `future_hours` are added to the URL, and the result's
         * [GuideDownload.Loaded.window] is the part of [window] the pool sent, worked out from
         * its `Date` header ([clock] when it has none). Blocking; call off the main thread.
         */
        fun downloadGuide(
            http: OkHttpClient,
            url: String,
            window: EpgWindow?,
            channelIds: Set<String>?,
            ifNoneMatch: String?,
            pool: EpgCachePolicy.PoolGuideRequest? = null,
            clock: () -> Long = System::currentTimeMillis,
        ): GuideDownload {
            val windowed = pool?.let { p ->
                url.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("past_hours", p.pastHours.toString())
                    ?.addQueryParameter("future_hours", p.futureHours.toString())
                    ?.build()
            }
            val request = Request.Builder()
                .apply { if (windowed != null) url(windowed) else url(url) }
                .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
                .build()
            return http.newCall(request).execute().use { response ->
                if (ifNoneMatch != null && response.code == HTTP_NOT_MODIFIED) return@use GuideDownload.NotModified
                check(response.isSuccessful) { "Provider guide request failed" }
                val held = if (windowed != null && pool != null && window != null) {
                    val serverNow = response.headers.getDate("Date")?.time ?: clock()
                    EpgCachePolicy.poolGuideCovered(pool, window, serverNow)
                        ?: error("The pool's guide window doesn't overlap the one asked for")
                } else {
                    null
                }
                val body = response.body ?: error("Empty EPG response")
                val guide = decodedStream(body.byteStream(), response.header("Content-Encoding")).use { xml ->
                    InputStreamReader(xml, Charsets.UTF_8).use { reader ->
                        XmltvParser.parse(reader, window, channelIds)
                    }
                }
                GuideDownload.Loaded(guide, response.header("ETag"), held)
            }
        }
    }
}
