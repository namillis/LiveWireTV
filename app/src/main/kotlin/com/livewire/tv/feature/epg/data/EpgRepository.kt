package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the XMLTV guide from the user's Xtream provider (xmltv.php) and parses it.
 * Handles gzip transparently (many providers gzip the guide). Kotlin port of the
 * Flutter EpgRepository.
 */
@Singleton
class EpgRepository @Inject constructor(
    private val http: OkHttpClient,
) {
    fun xmltvUrl(cfg: ProviderConfig): String =
        "${cfg.baseUrl}/xmltv.php?username=${enc(cfg.username)}&password=${enc(cfg.password)}"

    /** Fetch + parse the guide. Throws on network/parse failure (caller handles). */
    suspend fun fetch(cfg: ProviderConfig): EpgGuide = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(xmltvUrl(cfg)).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body ?: error("Empty EPG response")
            val bytes = body.bytes()
            val xml = if (isGzip(bytes)) {
                GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
            } else {
                String(bytes, Charsets.UTF_8)
            }
            XmltvParser.parse(xml)
        }
    }

    private fun isGzip(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
