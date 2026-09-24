package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the XMLTV guide from the user's Xtream provider (xmltv.php) and parses it
 * directly from the response stream. [window] bounds retained programme data during
 * parsing, which keeps large provider guides viable on low-memory TV hardware.
 */
@Singleton
class EpgRepository @Inject constructor(
    private val http: OkHttpClient,
) {
    fun xmltvUrl(cfg: ProviderConfig): String =
        "${cfg.baseUrl}/xmltv.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}"

    /** Fetch and stream-parse the guide. Throws on network or parse failure. */
    suspend fun fetch(cfg: ProviderConfig, window: EpgWindow? = null): EpgGuide =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(xmltvUrl(cfg)).build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Provider guide request failed" }
                val body = response.body ?: error("Empty EPG response")
                body.byteStream().use { rawStream ->
                    val buffered = BufferedInputStream(rawStream)
                    xmlStream(buffered, response.header("Content-Encoding")).use { xmlStream ->
                        InputStreamReader(xmlStream, Charsets.UTF_8).use { reader ->
                            XmltvParser.parse(reader, window)
                        }
                    }
                }
            }
        }

    private fun xmlStream(stream: BufferedInputStream, contentEncoding: String?): InputStream =
        if (contentEncoding.equals("gzip", ignoreCase = true) || isGzip(stream)) {
            GZIPInputStream(stream)
        } else {
            stream
        }

    private fun isGzip(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return first == 0x1f && second == 0x8b
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
