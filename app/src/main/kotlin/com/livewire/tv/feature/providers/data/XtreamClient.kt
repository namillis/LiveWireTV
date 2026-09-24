package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
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

    /** Validate credentials via user_info. */
    suspend fun authenticate(cfg: ProviderConfig): ProviderAuthResult = withContext(Dispatchers.IO) {
        try {
            val body = get(cfg.playerApiUrl()) ?: return@withContext ProviderAuthResult(
                ok = false, message = "Empty response from provider",
            )
            val root = json.parseToJsonElement(body).jsonObject
            val info = root["user_info"]?.jsonObject
                ?: return@withContext ProviderAuthResult(ok = false, message = "No user_info in response")
            val auth = info["auth"]?.jsonPrimitive?.contentOrNull
            val ok = auth == "1" || auth == "1.0"
            ProviderAuthResult(
                ok = ok,
                status = info["status"]?.jsonPrimitive?.contentOrNull,
                expiry = info["exp_date"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                maxConnections = info["max_connections"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                message = if (ok) null else "Authentication failed (check URL / credentials)",
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
                LiveChannel(
                    streamId = o["stream_id"].str().orEmpty(),
                    name = o["name"].str().orEmpty(),
                    logoUrl = o["stream_icon"].str()?.ifBlank { null },
                    epgChannelId = o["epg_channel_id"].str()?.ifBlank { null },
                    categoryId = o["category_id"].str().orEmpty(),
                )
            }
        }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }
}

/** Xtream fields arrive as strings or numbers depending on the provider — read either. */
private fun kotlinx.serialization.json.JsonElement?.str(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
