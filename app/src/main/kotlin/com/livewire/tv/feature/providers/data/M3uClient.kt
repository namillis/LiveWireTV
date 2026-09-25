package com.livewire.tv.feature.providers.data

import com.livewire.tv.core.net.decodedStream
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStreamReader
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A parsed playlist whose entries are addressable by a stable, non-secret id. */
class LoadedPlaylist(val playlist: M3uPlaylist) {
    val entriesById: Map<String, M3uEntry> = LinkedHashMap<String, M3uEntry>().apply {
        for (entry in playlist.entries) putIfAbsent(M3uClient.streamIdFor(entry.url), entry)
    }
}

/**
 * Downloads and parses M3U playlists directly from the user's provider.
 *
 * Home, Guide, Search, Sports, and Player each ask for channels, so the most recent
 * playlist is kept in memory for a short time. Only ONE playlist is cached, which
 * bounds memory on low-RAM TV sticks while avoiding a download per screen.
 */
@Singleton
class M3uClient @Inject constructor(
    private val http: OkHttpClient,
) {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedAt: Long = 0
    private var cached: LoadedPlaylist? = null

    suspend fun load(cfg: ProviderConfig, forceRefresh: Boolean = false): LoadedPlaylist =
        mutex.withLock {
            val key = "${cfg.id}|${cfg.baseUrl}"
            val now = System.currentTimeMillis()
            val current = cached
            if (!forceRefresh && current != null && cachedKey == key && now - cachedAt < CACHE_TTL_MS) {
                return@withLock current
            }
            // Drop the old playlist before downloading, so two never coexist in memory.
            cached = null
            cachedKey = null
            val loaded = LoadedPlaylist(download(cfg.baseUrl.trim()))
            cached = loaded
            cachedKey = key
            cachedAt = now
            loaded
        }

    /** Frees the cached playlist, for example after a provider is deleted. */
    suspend fun clear() = mutex.withLock {
        cached = null
        cachedKey = null
    }

    private suspend fun download(url: String): M3uPlaylist = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Playlist request failed" }
            val body = response.body ?: error("Empty playlist response")
            decodedStream(body.byteStream(), response.header("Content-Encoding")).use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader -> M3uParser.parse(reader) }
            }
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 15 * 60 * 1000L

        /**
         * Stable id for an entry, derived from its URL. Stream URLs often embed
         * credentials, so the id is a one-way hash and never the URL itself.
         */
        fun streamIdFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
