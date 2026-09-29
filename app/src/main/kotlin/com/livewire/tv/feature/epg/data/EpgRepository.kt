package com.livewire.tv.feature.epg.data

import com.livewire.tv.core.net.decodedStream
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 */
@Singleton
class EpgRepository @Inject constructor(
    private val http: OkHttpClient,
    private val providers: ProviderRepository,
    private val cache: EpgCache,
) {
    /**
     * Fetch the guide and return it filtered to [channelIds] (all channels when null) and to
     * [window]. Returns a cached guide when one is fresh; otherwise downloads and parses the
     * provider's full guide once, caches it per provider, and filters the result. Throws on
     * network/parse failure or when the provider has no guide (and nothing is cached).
     *
     * The cache holds ONE superset entry per provider (all channels, a widened window), so
     * every screen shares it: Guide and Search no longer download the guide once each.
     *
     * @param forceRefresh bypasses the cache and re-downloads (used by an explicit refresh).
     */
    suspend fun fetch(
        cfg: ProviderConfig,
        window: EpgWindow? = null,
        channelIds: Set<String>? = null,
        forceRefresh: Boolean = false,
    ): EpgGuide {
        val url = providers.guideUrl(cfg) ?: error("No guide configured for this provider")
        val key = EpgCachePolicy.keyFor(cfg, url)
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
        ) {
            download(url, parsedWindow, channelIds = null)
        }
    }

    private suspend fun download(
        url: String,
        window: EpgWindow?,
        channelIds: Set<String>?,
    ): EpgGuide = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Provider guide request failed" }
            val body = response.body ?: error("Empty EPG response")
            decodedStream(body.byteStream(), response.header("Content-Encoding")).use { xml ->
                InputStreamReader(xml, Charsets.UTF_8).use { reader ->
                    XmltvParser.parse(reader, window, channelIds)
                }
            }
        }
    }
}
