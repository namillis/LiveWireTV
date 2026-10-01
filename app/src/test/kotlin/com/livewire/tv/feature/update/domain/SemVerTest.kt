package com.livewire.tv.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test fun `numeric identifiers compare numerically not lexically`() {
        assertTrue(isNewerRelease(current = "0.1.9", candidate = "0.1.10"))
        assertFalse(isNewerRelease(current = "0.1.10", candidate = "0.1.9"))
    }

    @Test fun `equal versions are not newer`() {
        assertFalse(isNewerRelease("0.1.3", "0.1.3"))
        assertFalse(isNewerRelease("0.1.3", "v0.1.3"))
    }

    @Test fun `major and minor take precedence`() {
        assertTrue(isNewerRelease("0.9.9", "1.0.0"))
        assertTrue(isNewerRelease("0.1.9", "0.2.0"))
        assertFalse(isNewerRelease("1.0.0", "0.9.9"))
    }

    @Test fun `leading v is stripped on both sides`() {
        assertTrue(isNewerRelease("v0.1.3", "v0.1.4"))
    }

    @Test fun `dev suffix sorts older than the plain release of the same numbers`() {
        // A running 0.1.4-dev should see the real 0.1.4 as newer.
        assertTrue(isNewerRelease(current = "0.1.4-dev", candidate = "0.1.4"))
        // And 0.1.4 release should NOT see 0.1.4-dev as newer.
        assertFalse(isNewerRelease(current = "0.1.4", candidate = "0.1.4-dev"))
    }

    @Test fun `debug suffix is a pre-release and sorts below the release`() {
        assertTrue(isNewerRelease(current = "0.1.4-debug", candidate = "0.1.4"))
        assertFalse(isNewerRelease(current = "0.1.4", candidate = "0.1.4-debug"))
    }

    @Test fun `a higher numeric release beats any pre-release of a lower one`() {
        assertTrue(isNewerRelease(current = "0.1.4-dev", candidate = "0.1.5"))
    }

    @Test fun `bad tags are rejected and never look newer`() {
        assertFalse(isNewerRelease(current = "0.1.3", candidate = "latest"))
        assertFalse(isNewerRelease(current = "0.1.3", candidate = "notaversion"))
        assertFalse(isNewerRelease(current = "0.1.3", candidate = ""))
        assertFalse(isNewerRelease(current = "0.1.3", candidate = "0.x.4"))
        // A bad CURRENT is also safe: never offer an update we can't reason about.
        assertFalse(isNewerRelease(current = "garbage", candidate = "0.1.4"))
    }

    @Test fun `parse accepts partial cores`() {
        assertEquals(SemVer(1, 0, 0, false), SemVer.parse("1"))
        assertEquals(SemVer(1, 2, 0, false), SemVer.parse("1.2"))
        assertEquals(SemVer(1, 2, 3, false), SemVer.parse("1.2.3"))
    }

    @Test fun `parse treats plus-build as non-prerelease`() {
        val v = SemVer.parse("0.1.4+build7")
        assertEquals(SemVer(0, 1, 4, false), v)
    }

    @Test fun `parse rejects negative and non-numeric`() {
        assertNull(SemVer.parse("-1.0.0"))
        assertNull(SemVer.parse("a.b.c"))
        assertNull(SemVer.parse(null))
    }
}
