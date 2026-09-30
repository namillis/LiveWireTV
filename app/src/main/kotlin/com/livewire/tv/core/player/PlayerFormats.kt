package com.livewire.tv.core.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure formatting for the player overlay and Options panel. No Android/Compose calls, so it
 * is unit-testable. All strings the overlay shows about tracks, quality and timing are built
 * here rather than inline in composables.
 */
object PlayerFormats {

    /** "Stereo", "5.1", "Mono", "" for an unknown channel count. */
    fun channelLayout(channelCount: Int): String = when {
        channelCount <= 0 -> ""
        channelCount == 1 -> "Mono"
        channelCount == 2 -> "Stereo"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "$channelCount ch"
    }

    /** "English · Stereo", "English", "Audio 1 · 5.1", collapsing empty parts. */
    fun audioSummary(label: String, channelCount: Int): String {
        val layout = channelLayout(channelCount)
        return listOf(label, layout).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** "1080p", "720p", "2160p", or "" when the height is unknown. */
    fun resolutionLabel(width: Int, height: Int): String = when {
        height <= 0 -> ""
        else -> "${height}p"
    }

    /**
     * The channel sub-line under the mark: "CH 202 · 1080p · Stereo · CC". Every part is
     * optional and dropped when unknown, so a channel with no track info reads just "CH 202".
     */
    fun channelSubline(
        channelNumber: String?,
        video: VideoFormat?,
        audioChannelCount: Int,
        hasSubtitles: Boolean,
    ): String {
        val parts = buildList {
            channelNumber?.takeIf { it.isNotBlank() }?.let { add("CH $it") }
            video?.let { resolutionLabel(it.width, it.height) }?.takeIf { it.isNotBlank() }?.let { add(it) }
            channelLayout(audioChannelCount).takeIf { it.isNotBlank() }?.let { add(it) }
            if (hasSubtitles) add("CC")
        }
        return parts.joinToString(" · ")
    }

    /** "1080p · H.264", the compact value on the Stream info row; "" when nothing is known. */
    fun streamInfoSummary(video: VideoFormat?): String {
        if (video == null) return ""
        return listOf(
            resolutionLabel(video.width, video.height),
            video.videoCodec.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** "24.0 Mbps", "850 kbps", or "" for an unknown/zero bitrate. */
    fun bitrateLabel(bitrateBps: Int): String = when {
        bitrateBps <= 0 -> ""
        bitrateBps >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bitrateBps / 1_000_000.0)
        else -> "${bitrateBps / 1000} kbps"
    }

    /** "60 fps", "29.97 fps", or "" for an unknown frame rate. */
    fun frameRateLabel(frameRate: Float): String = when {
        frameRate <= 0f -> ""
        // Whole frame rates print without a decimal; fractional ones keep two places.
        frameRate % 1f == 0f -> "${frameRate.toInt()} fps"
        else -> String.format(Locale.ROOT, "%.2f fps", frameRate)
    }

    /** The detail rows for the Stream info sub-list: (label, value), dropping unknown values. */
    fun streamInfoRows(video: VideoFormat?): List<Pair<String, String>> {
        if (video == null) return emptyList()
        return buildList {
            resolutionLabel(video.width, video.height).takeIf { it.isNotBlank() }?.let { add("Resolution" to it) }
            frameRateLabel(video.frameRate).takeIf { it.isNotBlank() }?.let { add("Frame rate" to it) }
            video.videoCodec?.takeIf { it.isNotBlank() }?.let { add("Video codec" to it) }
            video.audioCodec?.takeIf { it.isNotBlank() }?.let { add("Audio codec" to it) }
            channelLayout(video.audioChannelCount).takeIf { it.isNotBlank() }?.let { add("Audio" to it) }
            bitrateLabel(video.bitrateBps).takeIf { it.isNotBlank() }?.let { add("Bitrate" to it) }
        }
    }

    // --- Time formatting -----------------------------------------------------------------
    // Zone-injectable so tests are deterministic. "h:mm" gives "8:00"; "h:mm a" gives "8:00 PM".

    private val hourMinute = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private val hourMinuteMeridiem = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    private fun format(ms: Long, fmt: DateTimeFormatter, zone: ZoneId): String =
        fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "8:00" — no meridiem, for the progress-bar endpoints where both ends share AM/PM. */
    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = format(ms, hourMinute, zone)

    /** "8:24 PM" — with meridiem, for the top-right wall clock. */
    fun clockMeridiem(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, hourMinuteMeridiem, zone)

    /**
     * "8:00–9:00 PM" — a programme's start–end, meridiem shown once at the end. Used on the
     * channel-list rows and the preview. Returns "" when either endpoint is missing.
     */
    fun timeRange(startMs: Long, stopMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (startMs <= 0L || stopMs <= 0L) return ""
        return "${clock(startMs, zone)}–${clockMeridiem(stopMs, zone)}"
    }

    /** "36 min left", "ends soon" in the last minute, or "" when nothing is on. */
    fun minutesLeft(stopMs: Long, now: Long): String {
        if (stopMs <= 0L) return ""
        val mins = ((stopMs - now) / 60_000L)
        return when {
            mins < 1 -> "ends soon"
            else -> "$mins min left"
        }
    }
}
