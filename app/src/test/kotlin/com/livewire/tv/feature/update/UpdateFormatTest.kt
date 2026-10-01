package com.livewire.tv.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure update-UI formatting in [UpdateFormat]. */
class UpdateFormatTest {

    // ── size ──────────────────────────────────────────────────────────────────────

    @Test fun `size renders MB with one decimal, trailing zero trimmed`() {
        assertEquals("3.6 MB", UpdateFormat.size(3_600_000))
        assertEquals("2.3 MB", UpdateFormat.size(2_300_000))
        // 3_000_000 -> "3 MB", not "3.0 MB".
        assertEquals("3 MB", UpdateFormat.size(3_000_000))
    }

    @Test fun `size renders KB and bytes for small values`() {
        assertEquals("850 KB", UpdateFormat.size(850_000))
        assertEquals("512 B", UpdateFormat.size(512))
    }

    @Test fun `size renders GB above a gigabyte`() {
        assertEquals("1.2 GB", UpdateFormat.size(1_200_000_000))
    }

    @Test fun `size of zero or negative is an em dash`() {
        assertEquals("—", UpdateFormat.size(0))
        assertEquals("—", UpdateFormat.size(-5))
    }

    @Test fun `progressBytes reads N of M`() {
        assertEquals("2.3 MB of 3.6 MB", UpdateFormat.progressBytes(2_300_000, 3_600_000))
    }

    // ── released fragment ──────────────────────────────────────────────────────────

    @Test fun `releasedFragment prefixes the date label`() {
        assertEquals("Released Sep 30", UpdateFormat.releasedFragment("Sep 30"))
    }

    @Test fun `releasedFragment is null when no date`() {
        assertNull(UpdateFormat.releasedFragment(null))
    }

    // ── notes parsing ────────────────────────────────────────────────────────────

    @Test fun `notesLines strips markdown bullet markers and marks them as bullets`() {
        val body = "- First thing\n* Second thing\n+ Third thing"
        val lines = UpdateFormat.notesLines(body)
        assertEquals(listOf("First thing", "Second thing", "Third thing"), lines.map { it.text })
        assertTrue(lines.all { it.bullet })
    }

    @Test fun `notesLines keeps plain lines as non-bullet text`() {
        val lines = UpdateFormat.notesLines("Just a line\nAnother line")
        assertEquals(listOf("Just a line", "Another line"), lines.map { it.text })
        assertTrue(lines.none { it.bullet })
    }

    @Test fun `notesLines lead-in line is plain, following list items are bullets`() {
        val body = "What's new in this build:\n- A fix\n- Another fix"
        val lines = UpdateFormat.notesLines(body)
        assertEquals("What's new in this build:", lines[0].text)
        assertTrue(!lines[0].bullet)
        assertTrue(lines[1].bullet && lines[2].bullet)
    }

    @Test fun `notesLines drops blank lines and rules, keeps heading text as plain`() {
        val body = "# What's new\n\n- A fix\n\n---\n\nMore text"
        val lines = UpdateFormat.notesLines(body)
        assertEquals(listOf("What's new", "A fix", "More text"), lines.map { it.text })
        assertEquals(listOf(false, true, false), lines.map { it.bullet })
    }

    @Test fun `notesLines strips inline emphasis markers`() {
        val lines = UpdateFormat.notesLines("- Fixed **the crash** and `the parser`")
        assertEquals(listOf("Fixed the crash and the parser"), lines.map { it.text })
    }

    @Test fun `notesLines is empty for null or blank`() {
        assertTrue(UpdateFormat.notesLines(null).isEmpty())
        assertTrue(UpdateFormat.notesLines("   \n  ").isEmpty())
    }

    // ── version line / clean ────────────────────────────────────────────────────

    @Test fun `versionLine formats name and build`() {
        assertEquals("LiveWire · 0.1.3 (build 47)", UpdateFormat.versionLine("0.1.3", 47))
    }

    @Test fun `cleanVersion strips the debug suffix`() {
        assertEquals("0.1.3", UpdateFormat.cleanVersion("0.1.3-debug"))
        assertEquals("0.1.3", UpdateFormat.cleanVersion("0.1.3"))
    }

    // ── About check-for-updates subtitles ────────────────────────────────────────

    @Test fun `subtitles read as the mockup`() {
        assertEquals("You're on the latest version (0.1.3)", UpdateFormat.upToDateSubtitle("0.1.3"))
        assertEquals("Version 0.1.4 is available", UpdateFormat.availableSubtitle("0.1.4"))
        assertEquals("Checking…", UpdateFormat.CHECKING_SUBTITLE)
        assertEquals("Updates are off in debug builds", UpdateFormat.DEBUG_DISABLED_SUBTITLE)
    }

    // ── last-checked relative time ───────────────────────────────────────────────

    private val now = 1_000_000_000_000L

    @Test fun `lastChecked never when zero`() {
        assertEquals("Never", UpdateFormat.lastCheckedLabel(0L, now, "Sep 30"))
    }

    @Test fun `lastChecked just now under a minute`() {
        assertEquals("Just now", UpdateFormat.lastCheckedLabel(now - 30_000, now, "Sep 30"))
    }

    @Test fun `lastChecked minutes and hours ago, singular and plural`() {
        assertEquals("1 minute ago", UpdateFormat.lastCheckedLabel(now - 60_000, now, "x"))
        assertEquals("5 minutes ago", UpdateFormat.lastCheckedLabel(now - 5 * 60_000, now, "x"))
        assertEquals("1 hour ago", UpdateFormat.lastCheckedLabel(now - 3_600_000, now, "x"))
        assertEquals("3 hours ago", UpdateFormat.lastCheckedLabel(now - 3 * 3_600_000, now, "x"))
    }

    @Test fun `lastChecked yesterday and days ago, then absolute`() {
        assertEquals("Yesterday", UpdateFormat.lastCheckedLabel(now - 86_400_000, now, "x"))
        assertEquals("3 days ago", UpdateFormat.lastCheckedLabel(now - 3 * 86_400_000L, now, "x"))
        // 8 days back falls through to the absolute label.
        assertEquals("Sep 20 · 8:00 PM", UpdateFormat.lastCheckedLabel(now - 8 * 86_400_000L, now, "Sep 20 · 8:00 PM"))
    }

    @Test fun `lastChecked future timestamp reads just now`() {
        assertEquals("Just now", UpdateFormat.lastCheckedLabel(now + 60_000, now, "x"))
    }

    // ── updated note ───────────────────────────────────────────────────────────

    @Test fun `updatedNote names the version`() {
        assertEquals("Updated to 0.0.2", UpdateFormat.updatedNote("0.0.2"))
    }

    // ── which state shows a dialog ───────────────────────────────────────────────

    @Test fun `isDialogState is true for the states the dialog renders`() {
        assertTrue(isDialogState(UpdateViewModel.UpdateUiState.Available("0.0.2", "0.0.1", "meta", emptyList())))
        assertTrue(isDialogState(UpdateViewModel.UpdateUiState.Downloading(50, 1, 2)))
        assertTrue(isDialogState(UpdateViewModel.UpdateUiState.Verifying))
        assertTrue(isDialogState(UpdateViewModel.UpdateUiState.Failed(com.livewire.tv.feature.update.domain.UpdateError.UNKNOWN)))
    }

    @Test fun `isDialogState is false for the silent states`() {
        assertTrue(!isDialogState(UpdateViewModel.UpdateUiState.Idle))
        assertTrue(!isDialogState(UpdateViewModel.UpdateUiState.Checking))
        assertTrue(!isDialogState(UpdateViewModel.UpdateUiState.UpToDate("0.0.1")))
        assertTrue(!isDialogState(UpdateViewModel.UpdateUiState.Installing))
    }
}
