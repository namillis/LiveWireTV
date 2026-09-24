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
 */
@Singleton
class EpgRepository @Inject constructor(
    private val http: OkHttpClient,
    private val providers: ProviderRepository,
) {
    /** Fetch and stream-parse the guide. Throws on network/parse failure or no guide. */
    suspend fun fetch(cfg: ProviderConfig, window: EpgWindow? = null): EpgGuide {
        val url = providers.guideUrl(cfg) ?: error("No guide configured for this provider")
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Provider guide request failed" }
                val body = response.body ?: error("Empty EPG response")
                decodedStream(body.byteStream(), response.header("Content-Encoding")).use { xml ->
                    InputStreamReader(xml, Charsets.UTF_8).use { reader ->
                        XmltvParser.parse(reader, window)
                    }
                }
            }
        }
    }
}
