package com.livewire.tv.feature.catchup.domain

import com.livewire.tv.feature.providers.domain.CatchupSpec
import com.livewire.tv.feature.providers.domain.CatchupType
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.feature.providers.domain.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** No real credentials anywhere here — all hosts/users/passwords are obvious fakes. */
class ArchiveUrlBuilderTest {

    private val xtreamCfg = ProviderConfig(
        id = "p1", name = "Test", baseUrl = "http://example.test:8080",
        username = "u", password = "p", type = ProviderType.XTREAM,
    )

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun xtreamChannel(days: Int = 3) = LiveChannel(
        streamId = "555", name = "CNN", categoryId = "1", archiveDays = days,
    )

    @Test fun `xtream primary path form, UTC zone`() {
        val now = utc(2026, 9, 30, 19, 0)
        val start = utc(2026, 9, 30, 17, 0)
        val src = ArchiveUrlBuilder.archiveUrl(
            xtreamCfg, xtreamChannel(), start, 60,
            serverInfo = ServerInfo(timezone = "UTC"), nowMs = now,
        )!!
        assertEquals("http://example.test:8080/timeshift/u/p/60/2026-09-30:17-00/555.ts", src.url)
    }

    @Test fun `xtream fallback php form`() {
        val now = utc(2026, 9, 30, 19, 0)
        val start = utc(2026, 9, 30, 17, 0)
        val url = ArchiveUrlBuilder.xtreamFallbackUrl(
            xtreamCfg, xtreamChannel(), start, 60, ServerInfo(timezone = "UTC"),
        )!!
        assertTrue(url.startsWith("http://example.test:8080/streaming/timeshift.php?"))
        assertTrue(url.contains("stream=555"))
        assertTrue(url.contains("start=2026-09-30%3A17-00"))
        assertTrue(url.contains("duration=60"))
    }

    @Test fun `xtream start formatted in provider local zone, not UTC`() {
        // New York is UTC-4 in September (EDT). 17:00 UTC = 13:00 local.
        val now = utc(2026, 9, 30, 19, 0)
        val start = utc(2026, 9, 30, 17, 0)
        val src = ArchiveUrlBuilder.archiveUrl(
            xtreamCfg, xtreamChannel(), start, 60,
            serverInfo = ServerInfo(timezone = "America/New_York"), nowMs = now,
        )!!
        assertTrue(src.url.contains("/2026-09-30:13-00/"))
    }

    @Test fun `xtream across a DST fall-back boundary uses the correct offset`() {
        // US DST ends 2026-11-01 02:00 local. A start just before is EDT (-4), just after is EST (-5).
        val zone = ServerInfo(timezone = "America/New_York")
        val beforeUtc = utc(2026, 11, 1, 5, 30)   // 01:30 EDT
        val afterUtc = utc(2026, 11, 1, 7, 30)    // 02:30 EST
        val now = utc(2026, 11, 2, 0, 0)
        val ch = xtreamChannel(days = 7)
        val before = ArchiveUrlBuilder.archiveUrl(xtreamCfg, ch, beforeUtc, 30, zone, now)!!
        val after = ArchiveUrlBuilder.archiveUrl(xtreamCfg, ch, afterUtc, 30, zone, now)!!
        assertTrue("EDT before boundary", before.url.contains("/2026-11-01:01-30/"))
        assertTrue("EST after boundary", after.url.contains("/2026-11-01:02-30/"))
    }

    @Test fun `derived offset used when timezone missing`() {
        // time_now 19:32:35 with timestamp 1790796755 => the offset the provider reports.
        val info = ServerInfo(timeNow = "2026-09-30 19:32:35", timestampNow = 1790796755)
        // timestamp 1790796755 = 2026-09-30T19:32:35Z, so local == UTC => offset 0.
        val zone = info.resolveZone()
        assertEquals(ZoneOffset.UTC, zone.normalized())
    }

    @Test fun `derived offset rounds to nearest 15 minutes`() {
        val epoch = 1_790_000_000L
        val local = Instant.ofEpochSecond(epoch + 2 * 3600 + 7 * 60) // +2h07m raw
            .atZone(ZoneOffset.UTC).toLocalDateTime()
        val info = ServerInfo(
            timeNow = local.toString().replace('T', ' '),
            timestampNow = epoch,
        )
        // +2h07m rounds to +2h00m (nearest 15 min => 2:07 -> 2:00 since 7<7.5).
        assertEquals(ZoneOffset.ofHoursMinutes(2, 0), info.resolveZone())
    }

    @Test fun `no archive returns null`() {
        val now = utc(2026, 9, 30, 19, 0)
        val src = ArchiveUrlBuilder.archiveUrl(
            xtreamCfg, xtreamChannel(days = 0), utc(2026, 9, 30, 17, 0), 60, nowMs = now,
        )
        assertNull(src)
    }

    @Test fun `start before the window returns null`() {
        val now = utc(2026, 9, 30, 19, 0)
        // days=3 window opens at now-3d; a start 4 days back is out.
        val tooOld = now - 4L * 24 * 3600 * 1000
        assertNull(ArchiveUrlBuilder.archiveUrl(xtreamCfg, xtreamChannel(3), tooOld, 60, nowMs = now))
    }

    @Test fun `start at the far window edge is allowed`() {
        val now = utc(2026, 9, 30, 19, 0)
        val edge = now - 3L * 24 * 3600 * 1000 + 60_000 // just inside the 3-day edge
        assertTrue(ArchiveUrlBuilder.archiveUrl(xtreamCfg, xtreamChannel(3), edge, 60, ServerInfo(timezone = "UTC"), now) != null)
    }

    @Test fun `future start returns null`() {
        val now = utc(2026, 9, 30, 19, 0)
        assertNull(ArchiveUrlBuilder.archiveUrl(xtreamCfg, xtreamChannel(3), now + 60_000, 60, nowMs = now))
    }

    @Test fun `zero or negative duration returns null`() {
        val now = utc(2026, 9, 30, 19, 0)
        assertNull(ArchiveUrlBuilder.archiveUrl(xtreamCfg, xtreamChannel(3), utc(2026, 9, 30, 17, 0), 0, nowMs = now))
    }

    // --- M3U forms -----------------------------------------------------------------------

    private val m3uCfg = ProviderConfig(
        id = "m1", name = "M3U", baseUrl = "http://list.test/pl.m3u8", type = ProviderType.M3U,
    )

    private fun m3uChannel(spec: CatchupSpec) = LiveChannel(
        streamId = "abc", name = "Ch", categoryId = "g", archiveDays = spec.days, catchup = spec,
    )

    @Test fun `m3u shift appends utc and lutc`() {
        val now = 1_790_796_000_000L
        val start = now - 3600_000L
        val ch = m3uChannel(CatchupSpec(CatchupType.SHIFT, days = 7))
        val src = ArchiveUrlBuilder.archiveUrl(
            m3uCfg, ch, start, 60, nowMs = now, m3uLiveUrl = "http://s.test/live/ch1.ts",
        )!!
        assertEquals("http://s.test/live/ch1.ts?utc=${start / 1000}&lutc=${now / 1000}", src.url)
    }

    @Test fun `m3u default replaces url via template`() {
        val now = 1_790_796_000_000L
        val start = now - 7200_000L
        val spec = CatchupSpec(CatchupType.DEFAULT, source = "http://s.test/ts/{utc}-{duration}.m3u8", days = 7)
        val src = ArchiveUrlBuilder.archiveUrl(m3uCfg, m3uChannel(spec), start, 30, nowMs = now)!!
        assertEquals("http://s.test/ts/${start / 1000}-1800.m3u8", src.url)
    }

    @Test fun `m3u append concatenates source template`() {
        val now = 1_790_796_000_000L
        val start = now - 3600_000L
        val spec = CatchupSpec(CatchupType.APPEND, source = "?start={start}&end={end}", days = 7)
        val src = ArchiveUrlBuilder.archiveUrl(
            m3uCfg, m3uChannel(spec), start, 60, nowMs = now, m3uLiveUrl = "http://s.test/live/x.ts",
        )!!
        assertEquals("http://s.test/live/x.ts?start=${start / 1000}&end=${start / 1000 + 3600}", src.url)
    }

    @Test fun `m3u xc builds xtream-style timeshift from a live url`() {
        val now = utc(2026, 9, 30, 19, 0)
        val start = utc(2026, 9, 30, 17, 0)
        val spec = CatchupSpec(CatchupType.XC, days = 7)
        val src = ArchiveUrlBuilder.archiveUrl(
            m3uCfg, m3uChannel(spec), start, 60, nowMs = now,
            m3uLiveUrl = "http://xc.test/live/user/pass/9.ts",
        )!!
        assertEquals("http://xc.test/timeshift/user/pass/60/2026-09-30:17-00/9.ts", src.url)
    }

    @Test fun `template split date parts fill in UTC`() {
        // 2026-09-30 17:05:00 UTC
        val startSec = ZonedDateTime.of(2026, 9, 30, 17, 5, 0, 0, ZoneOffset.UTC).toEpochSecond()
        val out = ArchiveUrlBuilder.fillTemplate(
            "http://s.test/{Y}/{m}/{d}/{H}-{M}-{S}.ts", startSec, startSec + 3600, 60, startSec,
        )
        assertEquals("http://s.test/2026/09/30/17-05-00.ts", out)
    }

    @Test fun `template dollar-brace and duration-default forms`() {
        val startSec = 1_000_000L
        val out = ArchiveUrlBuilder.fillTemplate(
            "http://s.test/\${start}-\${end}-{duration:120}", startSec, startSec + 3600, 60, startSec + 10,
        )
        assertEquals("http://s.test/$startSec-${startSec + 3600}-120", out)
    }
}
