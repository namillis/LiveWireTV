package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks directly to the user's Xtream provider panel (player_api.php). No LiveWire
 * backend. Xtream returns heterogeneous shapes (object for user_info, arrays for
 * categories/streams) and providers vary, so we parse tolerantly from JsonElement.
 * Kotlin port of the Flutter XtreamClient.
 */
@Singleton
class XtreamClient @Inject constructor(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Latest `server_info` per provider id, refreshed on each [authenticate]. In-memory only. */
    private val serverInfoByProvider = ConcurrentHashMap<String, ServerInfo>()

    /** The last-seen server info for a provider, or null if it hasn't authenticated this session. */
    fun serverInfo(providerId: String): ServerInfo? = serverInfoByProvider[providerId]

    /** Validate credentials via user_info. */
    suspend fun authenticate(cfg: ProviderConfig): ProviderAuthResult = withContext(Dispatchers.IO) {
        try {
            val body = get(cfg.playerApiUrl()) ?: return@withContext ProviderAuthResult(
                ok = false, message = "Empty response from provider",
            )
            val root = json.parseToJsonElement(body).jsonObject
            val info = root["user_info"]?.jsonObject
                ?: return@withContext ProviderAuthResult(ok = false, message = "No user_info in response")
            val serverInfo = root["server_info"]?.jsonObject?.let { s ->
                ServerInfo(
                    timezone = s["timezone"].str()?.ifBlank { null },
                    timeNow = s["time_now"].str()?.ifBlank { null },
                    timestampNow = s["timestamp_now"].str()?.toLongOrNull(),
                )
            }
            if (serverInfo != null) serverInfoByProvider[cfg.id] = serverInfo
            val auth = info["auth"]?.jsonPrimitive?.contentOrNull
            val ok = auth == "1" || auth == "1.0"
            ProviderAuthResult(
                ok = ok,
                status = info["status"]?.jsonPrimitive?.contentOrNull,
                expiry = info["exp_date"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                maxConnections = info["max_connections"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                message = if (ok) null else "Authentication failed (check URL / credentials)",
                serverInfo = serverInfo,
            )
        } catch (_: Exception) {
            ProviderAuthResult(
                ok = false,
                message = "Could not reach the provider. Check the URL, credentials, and network.",
            )
        }
    }

    suspend fun liveCategories(cfg: ProviderConfig): List<ProviderCategory> = withContext(Dispatchers.IO) {
        val body = get(cfg.playerApiUrl(action = "get_live_categories")) ?: return@withContext emptyList()
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ProviderCategory(
                id = o["category_id"].str().orEmpty(),
                name = o["category_name"].str().orEmpty(),
            )
        }
    }

    suspend fun liveChannels(cfg: ProviderConfig, categoryId: String? = null): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
            val body = get(cfg.playerApiUrl(action = "get_live_streams", extra = extra))
                ?: return@withContext emptyList()
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val archived = o["tv_archive"].str()?.let { it == "1" || it == "1.0" || it.toIntOrNull() == 1 } ?: false
                val archiveDays = if (archived) o["tv_archive_duration"].str()?.toIntOrNull() ?: 0 else 0
                LiveChannel(
                    streamId = o["stream_id"].str().orEmpty(),
                    name = o["name"].str().orEmpty(),
                    logoUrl = o["stream_icon"].str()?.ifBlank { null },
                    epgChannelId = o["epg_channel_id"].str()?.ifBlank { null },
                    categoryId = o["category_id"].str().orEmpty(),
                    archiveDays = archiveDays,
                )
            }
        }

    /**
     * Per-channel past programme listing from `get_simple_data_table`, used to know which
     * past programmes are actually archived (`has_archive`) and their exact bounds.
     * Times come back as epoch-second strings, so they are zone-independent here.
     * Titles/descriptions are base64. Returns empty on any error (fail soft).
     */
    suspend fun simpleDataTable(cfg: ProviderConfig, streamId: String): List<ArchiveListing> =
        withContext(Dispatchers.IO) {
            val body = get(cfg.playerApiUrl(action = "get_simple_data_table", extra = mapOf("stream_id" to streamId)))
                ?: return@withContext emptyList()
            val listings = (json.parseToJsonElement(body) as? JsonObject)?.get("epg_listings") as? JsonArray
                ?: return@withContext emptyList()
            listings.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val start = o["start_timestamp"].str()?.toLongOrNull() ?: return@mapNotNull null
                val stop = o["stop_timestamp"].str()?.toLongOrNull() ?: return@mapNotNull null
                ArchiveListing(
                    startMs = start * 1000,
                    stopMs = stop * 1000,
                    title = o["title"].str().decodeBase64(),
                    description = o["description"].str().decodeBase64().ifBlank { null },
                    hasArchive = o["has_archive"].str()?.let { it == "1" || it.toIntOrNull() == 1 } ?: false,
                )
            }
        }

    /**
     * The pool extensions this provider supports (see [PoolSignals]), empty for a plain panel.
     * Null when the check failed on the network, so the caller can try again later. A panel
     * that doesn't answer within [POOL_CHECK_TIMEOUT_MS] counts as plain, so a slow panel
     * doesn't hold up every first play.
     */
    suspend fun poolFeatures(cfg: ProviderConfig): Set<String>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(cfg.playerApiUrl(action = "pool_info")).build()
            poolHttp.newCall(req).execute().use { resp ->
                if (resp.code >= 500) null else PoolSignals.parseFeatures(if (resp.isSuccessful) resp.body?.string() else null)
            }
        } catch (_: java.io.InterruptedIOException) {
            emptySet()
        } catch (_: java.io.IOException) {
            null
        }
    }

    private val poolHttp: OkHttpClient by lazy {
        http.newBuilder().callTimeout(POOL_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    }

    /** The pool's streams for [streamId] (`action=pool_sources`), empty on any error. */
    suspend fun poolSources(cfg: ProviderConfig, streamId: String): List<com.livewire.tv.feature.providers.domain.PoolSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                PoolSignals.parseSources(get(cfg.playerApiUrl(action = "pool_sources", extra = mapOf("stream_id" to streamId))))
            }.getOrDefault(emptyList())
        }

    /** Tell the pool playback of [streamId] stopped. Best effort: errors are ignored. */
    suspend fun poolStop(cfg: ProviderConfig, streamId: String) = withContext(Dispatchers.IO) {
        runCatching { get(cfg.playerApiUrl(action = "pool_stop", extra = mapOf("stream_id" to streamId))) }
        Unit
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private companion object {
        const val POOL_CHECK_TIMEOUT_MS = 3_000L
    }
}

/** Xtream fields arrive as strings or numbers depending on the provider — read either. */
private fun kotlinx.serialization.json.JsonElement?.str(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/** A past programme from `get_simple_data_table`, with its archive availability. Times are UTC epoch millis. */
data class ArchiveListing(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val hasArchive: Boolean,
)

/** Xtream base64-encodes title/description; decode leniently, falling back to the raw text. */
private fun String?.decodeBase64(): String {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    return runCatching {
        String(java.util.Base64.getMimeDecoder().decode(raw), Charsets.UTF_8)
    }.getOrDefault(raw)
}
