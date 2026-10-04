package com.livewire.tv.feature.catchup.domain

import com.livewire.tv.feature.providers.domain.CatchupType
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.feature.providers.domain.ServerInfo
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the archive (catch-up / time shift) playback URL for a programme, as a pure
 * function of the provider config, the channel, and the requested UTC start/duration.
 * No I/O and no ambient clock — everything it needs is passed in, so it is fully
 * unit-testable.
 *
 * **Security:** the returned [PlaybackSource.url] embeds credentials for both provider
 * types and is as secret as [ProviderConfig.liveStreamUrl] — keep it out of routes,
 * logs and error text.
 *
 * Returns null when the channel has no archive, the request falls outside the archive
 * window, or an M3U catch-up recipe can't be resolved (fail soft — the caller shows
 * "This programme isn't in the archive").
 *
 * For Xtream the builder needs only the panel path (from [cfg]); for M3U it needs the
 * channel's own live stream URL, which the repository resolves from the playlist and
 * passes as [m3uLiveUrl] (kept out of [LiveChannel], which carries only a hashed id).
 */
object ArchiveUrlBuilder {

    /** `YYYY-MM-DD:HH-MM` in the provider's local time — the Xtream timeshift path format. */
    private val xtreamStart = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT)

    fun archiveUrl(
        cfg: ProviderConfig,
        channel: LiveChannel,
        startMs: Long,
        durationMin: Int,
        serverInfo: ServerInfo? = null,
        nowMs: Long = System.currentTimeMillis(),
        m3uLiveUrl: String? = null,
        m3uHeaders: Map<String, String> = emptyMap(),
    ): PlaybackSource? {
        if (!channel.hasArchive) return null
        if (durationMin <= 0) return null
        // Reject a start beyond the archive window or in the future.
        val windowStartMs = nowMs - channel.archiveDays.toLong() * 24 * 60 * 60 * 1000
        if (startMs < windowStartMs || startMs > nowMs) return null

        return when (cfg.type) {
            ProviderType.XTREAM ->
                PlaybackSource(url = cfg.timeshiftStreamUrl(channel.streamId, durationMin, formatXtreamStart(startMs, serverInfo)))
            ProviderType.M3U ->
                m3uSource(channel, startMs, durationMin, nowMs, m3uLiveUrl, m3uHeaders)
        }
    }

    /** The Xtream fallback (`streaming/timeshift.php`) URL for the same request, for 404 retry. */
    fun xtreamFallbackUrl(
        cfg: ProviderConfig,
        channel: LiveChannel,
        startMs: Long,
        durationMin: Int,
        serverInfo: ServerInfo? = null,
    ): String? {
        if (cfg.type != ProviderType.XTREAM || !channel.hasArchive) return null
        return cfg.timeshiftPhpUrl(channel.streamId, durationMin, formatXtreamStart(startMs, serverInfo))
    }

    private fun formatXtreamStart(startMs: Long, serverInfo: ServerInfo?): String {
        val zone = serverInfo?.resolveZone() ?: ZoneOffset.UTC
        return xtreamStart.format(Instant.ofEpochMilli(startMs).atZone(zone))
    }

    private fun m3uSource(
        channel: LiveChannel,
        startMs: Long,
        durationMin: Int,
        nowMs: Long,
        liveUrl: String?,
        headers: Map<String, String>,
    ): PlaybackSource? {
        val spec = channel.catchup ?: return null
        val startSec = startMs / 1000
        val endSec = startSec + durationMin.toLong() * 60
        val nowSec = nowMs / 1000

        val url = when (spec.type) {
            CatchupType.SHIFT -> {
                val base = liveUrl ?: return null
                val sep = if (base.contains('?')) '&' else '?'
                "$base${sep}utc=$startSec&lutc=$nowSec"
            }
            CatchupType.APPEND -> {
                val base = liveUrl ?: return null
                val src = spec.source ?: return null
                base + fillTemplate(src, startSec, endSec, durationMin, nowSec)
            }
            CatchupType.DEFAULT -> {
                val src = spec.source ?: return null
                fillTemplate(src, startSec, endSec, durationMin, nowSec)
            }
            CatchupType.FLUSSONIC -> {
                val src = spec.source ?: return null
                fillTemplate(src, startSec, endSec, durationMin, nowSec)
            }
            CatchupType.XC -> xcUrl(liveUrl ?: return null, startMs, durationMin) ?: return null
        }
        return PlaybackSource(url = url, headers = headers)
    }

    /**
     * Substitute catch-up template placeholders. Supports the plain and `${...}` forms,
     * `{duration:N}` / `{offset:N}` defaults, and split date parts `{Y}{m}{d}{H}{M}{S}`
     * (evaluated in UTC).
     */
    fun fillTemplate(template: String, startSec: Long, endSec: Long, durationMin: Int, nowSec: Long): String {
        val durationSec = durationMin.toLong() * 60
        val parts = Instant.ofEpochSecond(startSec).atZone(ZoneOffset.UTC)
        var out = template
        val simple = mapOf(
            "utc" to startSec.toString(),
            "start" to startSec.toString(),
            "utcend" to endSec.toString(),
            "end" to endSec.toString(),
            "lutc" to nowSec.toString(),
            "now" to nowSec.toString(),
            "timestamp" to startSec.toString(),
            "duration" to durationSec.toString(),
            "Y" to "%04d".format(parts.year),
            "m" to "%02d".format(parts.monthValue),
            "d" to "%02d".format(parts.dayOfMonth),
            "H" to "%02d".format(parts.hour),
            "M" to "%02d".format(parts.minute),
            "S" to "%02d".format(parts.second),
        )
        for ((key, value) in simple) {
            // Replace the `${key}` form BEFORE `{key}`, otherwise the inner `{key}` match
            // consumes it and leaves a stray `$`.
            out = out.replace("\${$key}", value).replace("{$key}", value)
        }
        // {duration:N} / {offset:N}: N is a default used verbatim when present.
        out = Regex("""\$?\{duration:(\d+)\}""").replace(out) { it.groupValues[1] }
        out = Regex("""\$?\{offset:(\d+)\}""").replace(out) { it.groupValues[1] }
        return out
    }

    /** Build an Xtream-style timeshift URL from an M3U `/live/user/pass/id` URL (`xc` type). */
    private fun xcUrl(liveUrl: String, startMs: Long, durationMin: Int): String? {
        val match = Regex("""^(.*)/live/([^/]+)/([^/]+)/([^/]+?)(?:\.[A-Za-z0-9]+)?$""").find(liveUrl)
            ?: return null
        val (base, user, pass, id) = match.destructured
        val start = xtreamStart.format(Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC))
        return "$base/timeshift/$user/$pass/$durationMin/$start/$id.ts"
    }
}
