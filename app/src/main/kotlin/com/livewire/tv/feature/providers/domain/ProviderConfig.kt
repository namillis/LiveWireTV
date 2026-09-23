package com.livewire.tv.feature.providers.domain

import kotlinx.serialization.Serializable

/**
 * A user's IPTV (Xtream Codes) provider: portal URL + credentials, stored on-device.
 * Nothing here goes through any LiveWire backend. Ported from the Flutter prototype's
 * ProviderConfig.
 */
@Serializable
data class ProviderConfig(
    val id: String,          // local uuid
    val name: String,        // user-facing label
    val baseUrl: String,     // e.g. http://host:port  (Xtream panel root)
    val username: String,
    val password: String,
) {
    /** Xtream player_api URL for an action, with credentials attached. */
    fun playerApiUrl(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val params = buildMap {
            put("username", username)
            put("password", password)
            if (action != null) put("action", action)
            putAll(extra)
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${k.urlEncode()}=${v.urlEncode()}"
        }
        return "$baseUrl/player_api.php?$query"
    }

    /** Playable stream URL for a live channel (TS by default). */
    fun liveStreamUrl(streamId: String, ext: String = "ts"): String =
        "$baseUrl/live/$username/$password/$streamId.$ext"
}

private fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/** A live channel from the provider. */
@Serializable
data class LiveChannel(
    val streamId: String,
    val name: String,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val categoryId: String,
)

/** A live-stream category from the provider. */
@Serializable
data class ProviderCategory(
    val id: String,
    val name: String,
)

/** Result of validating a provider's credentials (Xtream user_info / auth). */
data class ProviderAuthResult(
    val ok: Boolean,
    val status: String? = null,        // "Active", "Expired", …
    val expiry: Long? = null,          // epoch seconds
    val maxConnections: Int? = null,
    val message: String? = null,
)
