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

    /** The pool lists a channel's streams (`action=pool_sources`) and plays a picked one (`source=`). */
    const val SOURCES = "sources"

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

    /** [url] opened on the stream the user picked ([PoolSource.key]) instead of the pool's choice. */
    fun pickedUrl(url: String, sourceKey: String): String =
        attemptUrl(url, 0) + "&source=" + java.net.URLEncoder.encode(sourceKey, "UTF-8")

    /** The streams in a `pool_sources` response, in the order the pool tries them. Never throws. */
    fun parseSources(body: String?): List<PoolSource> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val list = root["sources"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            fun flag(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull == "true"
            val key = str("source").ifBlank { return@mapNotNull null }
            PoolSource(
                key = key,
                provider = str("provider").ifBlank { key.substringBefore('.') },
                channelName = str("name"),
                quality = str("quality"),
                backup = flag("backup"),
                current = flag("current"),
                failed = flag("failed"),
            )
        }
    }
}

/** One stream a pool channel can play from. [key] goes back to the pool as `source=`. */
data class PoolSource(
    val key: String,
    val provider: String,
    val channelName: String = "",
    val quality: String = "",
    val backup: Boolean = false,
    val current: Boolean = false,
    val failed: Boolean = false,
) {
    /** "Teck · HD", "Nova · HD · Backup": what the Source list shows for this stream. */
    val label: String
        get() = listOf(provider, quality, if (backup) "Backup" else "").filter { it.isNotBlank() }.joinToString(" · ")
}
