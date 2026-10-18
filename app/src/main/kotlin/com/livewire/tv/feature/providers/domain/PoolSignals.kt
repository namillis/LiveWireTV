package com.livewire.tv.feature.providers.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    /** Live Now channels in `get_live_streams` carry a `pool_event` describing the game ([parseEvent]). */
    const val EVENT_INFO = "event_info"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A Live Now channel's `pool_event` as a programme on [streamId], or null when it's absent or
     * malformed (no title, a missing time, or an end not after the start), so the channel falls
     * back to the guide. Times arrive as Unix seconds. Never throws.
     */
    fun parseEvent(element: JsonElement?, streamId: String): EpgProgramme? {
        val o = element as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        fun seconds(k: String) = str(k)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        val title = str("title") ?: return null
        val start = seconds("start")?.takeIf { it > 0 } ?: return null
        val end = seconds("end")?.takeIf { it > start } ?: return null
        return EpgProgramme(
            channelId = streamId,
            startMs = start * 1000,
            stopMs = end * 1000,
            title = title,
            description = str("description"),
            category = str("category"),
            endIsEstimate = true,
        )
    }

    /**
     * [channels] as the app should use them given the provider's [features]: events are kept only
     * when the pool advertises [EVENT_INFO], so a plain panel that happens to send a `pool_event`
     * field never changes what LiveWire shows.
     */
    fun withEvents(channels: List<LiveChannel>, features: Set<String>): List<LiveChannel> =
        if (EVENT_INFO in features || channels.none { it.poolEvent != null }) channels
        else channels.map { if (it.poolEvent == null) it else it.copy(poolEvent = null) }

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
     * with `why=refused` when the provider refused the connection (one of the player's
     * `RefusedCodes`, such as 403 or 456).
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
