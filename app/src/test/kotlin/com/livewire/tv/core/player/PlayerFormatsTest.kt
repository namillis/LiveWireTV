package com.livewire.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class PlayerFormatsTest {

    private val ny = ZoneId.of("America/New_York")

    // 2026-09-29 20:00:00 and 21:00:00 America/New_York (EDT).
    private val eightPm = 1790812800_000L
    private val ninePm = 1790816400_000L

    @Test fun `channel layout names common counts and falls back for the rest`() {
        assertEquals("", PlayerFormats.channelLayout(0))
        assertEquals("Mono", PlayerFormats.channelLayout(1))
        assertEquals("Stereo", PlayerFormats.channelLayout(2))
        assertEquals("5.1", PlayerFormats.channelLayout(6))
        assertEquals("7.1", PlayerFormats.channelLayout(8))
        assertEquals("4 ch", PlayerFormats.channelLayout(4))
    }

    @Test fun `audio summary joins label and layout, dropping blanks`() {
        assertEquals("English · Stereo", PlayerFormats.audioSummary("English", 2))
        assertEquals("English", PlayerFormats.audioSummary("English", 0))
        assertEquals("5.1", PlayerFormats.audioSummary("", 6))
    }

    @Test fun `channel subline drops every unknown part`() {
        val v = VideoFormat(width = 1920, height = 1080)
        assertEquals(
            "CH 202 · 1080p · Stereo · CC",
            PlayerFormats.channelSubline("202", v, audioChannelCount = 2, hasSubtitles = true),
        )
        // No video, no audio, no subtitles: just the channel number.
        assertEquals("CH 202", PlayerFormats.channelSubline("202", null, 0, false))
        // Nothing at all known.
        assertEquals("", PlayerFormats.channelSubline(null, null, 0, false))
    }

    @Test fun `stream info summary and rows drop unknowns`() {
        val v = VideoFormat(width = 1920, height = 1080, frameRate = 60f, videoCodec = "H.264", audioCodec = "AAC", audioChannelCount = 2, bitrateBps = 24_000_000)
        assertEquals("1080p · H.264", PlayerFormats.streamInfoSummary(v))
        assertEquals("", PlayerFormats.streamInfoSummary(null))
        val rows = PlayerFormats.streamInfoRows(v)
        assertEquals(
            listOf(
                "Resolution" to "1080p",
                "Frame rate" to "60 fps",
                "Video codec" to "H.264",
                "Audio codec" to "AAC",
                "Audio" to "Stereo",
                "Bitrate" to "24.0 Mbps",
            ),
            rows,
        )
    }

    @Test fun `bitrate and frame-rate labels format sensibly`() {
        assertEquals("24.0 Mbps", PlayerFormats.bitrateLabel(24_000_000))
        assertEquals("850 kbps", PlayerFormats.bitrateLabel(850_000))
        assertEquals("", PlayerFormats.bitrateLabel(0))
        assertEquals("60 fps", PlayerFormats.frameRateLabel(60f))
        assertEquals("29.97 fps", PlayerFormats.frameRateLabel(29.97f))
        assertEquals("", PlayerFormats.frameRateLabel(0f))
    }

    @Test fun `clock formats with and without meridiem in a fixed zone`() {
        assertEquals("8:00", PlayerFormats.clock(eightPm, ny))
        assertEquals("8:00 PM", PlayerFormats.clockMeridiem(eightPm, ny))
        assertEquals("9:00 PM", PlayerFormats.clockMeridiem(ninePm, ny))
    }

    @Test fun `time range shows the meridiem once at the end`() {
        assertEquals("8:00–9:00 PM", PlayerFormats.timeRange(eightPm, ninePm, ny))
        assertEquals("", PlayerFormats.timeRange(0L, ninePm, ny))
    }

    @Test fun `minutes-left rounds down and says ends soon in the final minute`() {
        val now = eightPm
        assertEquals("60 min left", PlayerFormats.minutesLeft(ninePm, now))
        assertEquals("ends soon", PlayerFormats.minutesLeft(now + 30_000L, now))
        assertEquals("", PlayerFormats.minutesLeft(0L, now))
    }
}

class TrackIdsTest {

    @Test fun `id round-trips through parse`() {
        assertEquals(2 to 5, TrackIds.parse(TrackIds.of(2, 5)))
        assertEquals(0 to 0, TrackIds.parse(TrackIds.of(0, 0)))
    }

    @Test fun `malformed ids parse to null`() {
        assertEquals(null, TrackIds.parse("2"))
        assertEquals(null, TrackIds.parse("a:b"))
        assertEquals(null, TrackIds.parse("2:3:4"))
        assertEquals(null, TrackIds.parse("-1:0"))
    }
}
