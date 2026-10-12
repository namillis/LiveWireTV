package com.livewire.tv.feature.providers.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The optional extensions the IPTV provider pool adds on top of plain Xtream. A provider answers
 * `player_api.php?action=pool_info` with `{"pool":1,"features":[...]}` when it is the pool; any
 * other answer means a plain panel, and LiveWire behaves exactly as it does for any Xtream provider.
 */
object PoolSignals {
    /** Live URLs carry `try=N`, so the pool fails over only when the app actually retries. */
    const val RETRY = "retry"

    /** The app reports when playback stops, so the pool frees that provider connection at once. */
    const val STOP = "stop"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The features in a `pool_info` response, or an empty set for anything that isn't the pool
     * (a plain panel's error page, `[]`, or `user_info`). Never throws.
     */
    fun parseFeatures(body: String?): Set<String> {
        if (body.isNullOrBlank()) return emptySet()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptySet()
        val pool = (root["pool"] as? JsonPrimitive)?.contentOrNull
        if (pool != "1") return emptySet()
        val features = root["features"] as? JsonArray ?: return emptySet()
        return features.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.ifBlank { null } }.toSet()
    }

    /**
     * [url] marked as attempt [attempt] of opening this stream: 0 on open, one more on each retry,
     * with `why=refused` when the provider answered 401/403/429.
     */
    fun attemptUrl(url: String, attempt: Int, refused: Boolean = false): String {
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}try=$attempt" + if (refused) "&why=refused" else ""
    }
}
