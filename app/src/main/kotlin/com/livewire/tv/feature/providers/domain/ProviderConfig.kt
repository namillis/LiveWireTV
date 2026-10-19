package com.livewire.tv.feature.providers.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** How LiveWire talks to a provider. Serialized, so never rename existing values. */
@Serializable
enum class ProviderType { XTREAM, M3U }

/**
 * A user's IPTV provider, stored on-device. Nothing here goes through any LiveWire
 * backend.
 *
 * - [ProviderType.XTREAM]: [baseUrl] is the panel root, with [username]/[password].
 * - [ProviderType.M3U]: [baseUrl] is the playlist URL and [epgUrl] an optional XMLTV
 *   guide. Playlist URLs commonly embed credentials, so treat [baseUrl] as secret for
 *   both types.
 *
 * New fields have defaults so configs stored by older builds still decode.
 */
@Serializable
data class ProviderConfig(
    val id: String,          // local uuid
    val name: String,        // user-facing label
    val baseUrl: String,     // Xtream panel root, or the M3U playlist URL
    val username: String = "",
    val password: String = "",
    val type: ProviderType = ProviderType.XTREAM,
    val epgUrl: String? = null,
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

    /**
     * Playable stream URL for a live channel (TS by default).
     *
     * Xtream puts credentials in path segments, so encode each segment independently.
     * The returned URL is security-sensitive and must stay out of navigation routes,
     * logs, errors, and persisted UI state.
     */
    fun liveStreamUrl(streamId: String, ext: String = "ts"): String =
        "$baseUrl/live/${username.pathSegmentEncode()}/${password.pathSegmentEncode()}/${streamId.pathSegmentEncode()}.${ext.pathSegmentEncode()}"

    /**
     * Xtream catch-up (TV archive) URL, primary path form:
     * `{base}/timeshift/{user}/{pass}/{durationMin}/{YYYY-MM-DD:HH-MM}/{streamId}.ts`.
     * [startFormatted] is the archive start in the PROVIDER's local time, already
     * formatted as `YYYY-MM-DD:HH-MM`. Security-sensitive like [liveStreamUrl].
     */
    fun timeshiftStreamUrl(streamId: String, durationMin: Int, startFormatted: String, ext: String = "ts"): String =
        "$baseUrl/timeshift/${username.pathSegmentEncode()}/${password.pathSegmentEncode()}/" +
            "$durationMin/$startFormatted/${streamId.pathSegmentEncode()}.${ext.pathSegmentEncode()}"

    /**
     * Xtream catch-up fallback form:
     * `{base}/streaming/timeshift.php?username=..&password=..&stream=..&start=..&duration=..`.
     * Used when the primary path form 404s. Security-sensitive like [liveStreamUrl].
     */
    fun timeshiftPhpUrl(streamId: String, durationMin: Int, startFormatted: String): String {
        val params = buildMap {
            put("username", username)
            put("password", password)
            put("stream", streamId)
            put("start", startFormatted)
            put("duration", durationMin.toString())
        }
        val query = params.entries.joinToString("&") { (k, v) -> "${k.urlEncode()}=${v.urlEncode()}" }
        return "$baseUrl/streaming/timeshift.php?$query"
    }

    fun usesCleartextTransport(): Boolean =
        baseUrl.isCleartext() || epgUrl?.isCleartext() == true

    /** Host only: safe to show in provider lists (never the full URL). */
    fun displayHost(): String =
        runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.ifBlank { null } ?: "Unknown host"
}

private fun String.isCleartext(): Boolean = trim().startsWith("http://", ignoreCase = true)

/** A non-secret reference passed between screens to resolve playback on demand. */
data class PlaybackTarget(
    val providerId: String,
    val streamId: String,
)

/**
 * Everything the player needs to open a stream. [url] is security-sensitive; keep
 * it out of routes, logs, and error text. [headers] carries per-channel HTTP
 * headers that M3U playlists declare (for example, `#EXTVLCOPT:http-user-agent`).
 */
data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /**
     * The URL for retry number `attempt` (1, 2, ...), `refused` when the provider refused the
     * connection (one of the player's `RefusedCodes`). Null means retries reopen [url]
     * unchanged, as for any plain provider.
     */
    val retryUrl: ((attempt: Int, refused: Boolean) -> String)? = null,
    /** Called once when the player stops this stream (switch, leave, preview end). */
    val onStop: (() -> Unit)? = null,
)

private fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

private fun String.pathSegmentEncode(): String = urlEncode()

/**
 * A live channel from the provider.
 *
 * [archiveDays] is how many days of catch-up (TV archive / time shift) the provider
 * offers for this channel; 0 means none. For Xtream it comes from
 * `tv_archive_duration`; for M3U it is [CatchupSpec.days] when a `catchup` attribute
 * is present.
 *
 * [catchup] is the M3U-only recipe for building an archive URL (Xtream builds its
 * archive URL from the panel path, so it needs no per-channel spec). New fields carry
 * defaults so channel data cached by older builds still decodes.
 *
 * [poolEvent] is the game a pool Live Now channel carries (`pool_event`, see
 * [PoolSignals.parseEvent]), shown instead of the guide for [epgChannelId], which belongs to the
 * real channel airing the game. Null for every other channel. Transient: it goes stale within
 * minutes, so it is never written anywhere.
 */
@Serializable
data class LiveChannel(
    val streamId: String,
    val name: String,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val categoryId: String,
    val archiveDays: Int = 0,
    val catchup: CatchupSpec? = null,
    @Transient val poolEvent: EpgProgramme? = null,
) {
    /** True when this channel offers any catch-up window. */
    val hasArchive: Boolean get() = archiveDays > 0
}

/** How an M3U channel exposes catch-up, from its `catchup*` attributes. */
@Serializable
enum class CatchupType { DEFAULT, APPEND, SHIFT, FLUSSONIC, XC }

/**
 * M3U catch-up recipe parsed from `#EXTINF` (`catchup` / `catchup-type`,
 * `catchup-source`, `catchup-days`) with header-level defaults from `#EXTM3U`.
 * [source] is a URL template and may embed credentials, so treat it as secret.
 */
@Serializable
data class CatchupSpec(
    val type: CatchupType,
    val source: String? = null,
    val days: Int = 0,
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
    /** Xtream `server_info` (timezone + clock), needed to build archive start times. Null for M3U. */
    val serverInfo: ServerInfo? = null,
)

/**
 * Xtream `server_info` from the `player_api.php` root response. Used to translate a
 * UTC archive instant into the provider's LOCAL wall time, which is what the timeshift
 * URL expects.
 *
 * - [timezone] is the IANA zone the provider reports (for example "UTC").
 * - [timeNow] is the provider's local wall time as a string ("2026-09-30 19:32:35"),
 *   with no zone.
 * - [timestampNow] is the matching epoch-second instant.
 *
 * Prefer [timezone] as a [java.time.ZoneId]. When it is missing or invalid, derive a
 * fixed offset from [timeNow] versus [timestampNow], rounded to the nearest 15 minutes.
 */
data class ServerInfo(
    val timezone: String? = null,
    val timeNow: String? = null,
    val timestampNow: Long? = null,
) {
    /** The provider's zone: [timezone] if valid, else a fixed offset derived from the clock, else UTC. */
    fun resolveZone(): java.time.ZoneId {
        timezone?.trim()?.takeIf { it.isNotEmpty() }?.let { tz ->
            runCatching { java.time.ZoneId.of(tz) }.getOrNull()?.let { return it }
        }
        derivedOffset()?.let { return it }
        return java.time.ZoneOffset.UTC
    }

    /**
     * Offset implied by comparing the reported local wall time to the epoch instant,
     * rounded to the nearest 15 minutes. Null when either field is missing/unparseable.
     */
    private fun derivedOffset(): java.time.ZoneOffset? {
        val local = timeNow?.trim() ?: return null
        val epoch = timestampNow ?: return null
        val parsed = runCatching {
            java.time.LocalDateTime.parse(local.replace(' ', 'T'))
        }.getOrNull() ?: return null
        val localAsUtc = parsed.toEpochSecond(java.time.ZoneOffset.UTC)
        val rawOffsetSec = localAsUtc - epoch
        val rounded = Math.round(rawOffsetSec / 900.0).toInt() * 900
        val clamped = rounded.coerceIn(-18 * 3600, 18 * 3600)
        return runCatching { java.time.ZoneOffset.ofTotalSeconds(clamped) }.getOrNull()
    }
}
