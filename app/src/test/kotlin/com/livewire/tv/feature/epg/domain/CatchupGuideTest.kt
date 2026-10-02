package com.livewire.tv.feature.epg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatchupGuideTest {

    private val now = 1_790_000_000_000L // fixed "now"
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test fun `a future or on-air cell is NORMAL regardless of archive`() {
        assertEquals(GuidePastCellStyle.NORMAL, CatchupGuide.cellStyle(now + hour, channelHasArchive = true, now))
        assertEquals(GuidePastCellStyle.NORMAL, CatchupGuide.cellStyle(now + hour, channelHasArchive = false, now))
        // Still airing (stops exactly at now counts as past; one ms later is on-air).
        assertEquals(GuidePastCellStyle.NORMAL, CatchupGuide.cellStyle(now + 1, channelHasArchive = false, now))
    }

    @Test fun `a past cell is ARCHIVED on an archive channel and DIMMED otherwise`() {
        assertEquals(GuidePastCellStyle.ARCHIVED_PAST, CatchupGuide.cellStyle(now - hour, channelHasArchive = true, now))
        assertEquals(GuidePastCellStyle.DIMMED_PAST, CatchupGuide.cellStyle(now - hour, channelHasArchive = false, now))
    }

    @Test fun `the inline badge shows only on a wide-enough archived past cell`() {
        assertTrue(CatchupGuide.showArchiveBadge(GuidePastCellStyle.ARCHIVED_PAST, 60))
        assertTrue(CatchupGuide.showArchiveBadge(GuidePastCellStyle.ARCHIVED_PAST, CatchupGuide.MIN_BADGE_MINUTES))
        // Too narrow (e.g. "Around the Horn" at 30 min): the inline badge is dropped.
        assertFalse(CatchupGuide.showArchiveBadge(GuidePastCellStyle.ARCHIVED_PAST, 30))
        // A dimmed or normal cell never gets the badge.
        assertFalse(CatchupGuide.showArchiveBadge(GuidePastCellStyle.DIMMED_PAST, 60))
        assertFalse(CatchupGuide.showArchiveBadge(GuidePastCellStyle.NORMAL, 60))
    }

    @Test fun `only a dimmed past cell reads as dimmed`() {
        assertTrue(CatchupGuide.isDimmed(GuidePastCellStyle.DIMMED_PAST))
        assertFalse(CatchupGuide.isDimmed(GuidePastCellStyle.ARCHIVED_PAST))
        assertFalse(CatchupGuide.isDimmed(GuidePastCellStyle.NORMAL))
    }

    @Test fun `a programme is playable catch-up only when past and flagged archived`() {
        assertTrue(CatchupGuide.isPlayableArchive(now - hour, hasArchiveFlag = true, now))
        // Past but not archived (too recent to be in the archive yet).
        assertFalse(CatchupGuide.isPlayableArchive(now - hour, hasArchiveFlag = false, now))
        // Flagged but still airing / future.
        assertFalse(CatchupGuide.isPlayableArchive(now + hour, hasArchiveFlag = true, now))
    }

    @Test fun `the past window start is lead minutes back, snapped to the half hour`() {
        val halfHour = TimeUnit.MINUTES.toMillis(30)
        val start = CatchupGuide.pastWindowStart(now, leadMinutes = 120)
        // Snapped down to the previous :00/:30, and no later than 120 min before now.
        assertEquals(0L, start % halfHour)
        assertTrue(start <= now - TimeUnit.MINUTES.toMillis(120))
        assertTrue(start > now - TimeUnit.MINUTES.toMillis(120) - halfHour)
        // A ragged now still snaps down to the previous :00/:30.
        val ragged = now + TimeUnit.MINUTES.toMillis(7)
        val raggedStart = CatchupGuide.pastWindowStart(ragged, leadMinutes = 120)
        assertEquals(0L, raggedStart % halfHour)
        assertTrue(raggedStart <= ragged - TimeUnit.MINUTES.toMillis(120))
    }

    @Test fun `the past lead follows the longest archive, capped at two days`() {
        assertEquals(2 * 24 * 60, CatchupGuide.pastLeadMinutes(maxArchiveDays = 2))
        assertEquals(24 * 60, CatchupGuide.pastLeadMinutes(maxArchiveDays = 1))
        // A 7-day archive still opens only two days back.
        assertEquals(2 * 24 * 60, CatchupGuide.pastLeadMinutes(maxArchiveDays = 7))
        // No archive days: never shorter than the default lead.
        assertEquals(CatchupGuide.DEFAULT_PAST_LEAD_MINUTES, CatchupGuide.pastLeadMinutes(maxArchiveDays = 0))
    }

    @Test fun `the archive window start reaches archiveDays back and is now for no archive`() {
        assertEquals(now - 2L * 24 * hour, CatchupGuide.archiveWindowStart(now, archiveDays = 2))
        assertEquals(now, CatchupGuide.archiveWindowStart(now, archiveDays = 0))
    }
}
