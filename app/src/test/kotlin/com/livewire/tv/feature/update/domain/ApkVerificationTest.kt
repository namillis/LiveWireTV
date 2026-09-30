package com.livewire.tv.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerificationTest {

    private val goodSha = "b".repeat(64)
    private val signer = setOf("cafebabe")

    private fun input(
        expectedSha: String? = goodSha,
        actualSha: String = goodSha,
        expectedSize: Long = 3500,
        actualSize: Long = 3500,
        pkg: String? = "com.livewire.tv",
        apkVc: Long? = 5,
        apkSigners: Set<String> = signer,
        installedPkg: String = "com.livewire.tv",
        installedVc: Long = 4,
        installedSigners: Set<String> = signer,
    ) = ApkVerificationInput(
        expectedSha256 = expectedSha,
        actualSha256 = actualSha,
        expectedSize = expectedSize,
        actualSize = actualSize,
        apkPackageName = pkg,
        apkVersionCode = apkVc,
        apkSignerDigests = apkSigners,
        installedPackageName = installedPkg,
        installedVersionCode = installedVc,
        installedSignerDigests = installedSigners,
    )

    @Test fun `all checks pass returns null`() {
        assertNull(verifyApk(input()))
    }

    @Test fun `size mismatch fails as checksum mismatch`() {
        assertEquals(UpdateError.CHECKSUM_MISMATCH, verifyApk(input(actualSize = 9999)))
    }

    @Test fun `checksum mismatch fails`() {
        assertEquals(UpdateError.CHECKSUM_MISMATCH, verifyApk(input(actualSha = "c".repeat(64))))
    }

    @Test fun `missing expected checksum is a failure not a pass`() {
        assertEquals(UpdateError.CHECKSUM_MISMATCH, verifyApk(input(expectedSha = null)))
    }

    @Test fun `unreadable archive is corrupt`() {
        assertEquals(UpdateError.CORRUPT_APK, verifyApk(input(pkg = null)))
        assertEquals(UpdateError.CORRUPT_APK, verifyApk(input(apkVc = null)))
    }

    @Test fun `wrong package fails`() {
        assertEquals(UpdateError.WRONG_PACKAGE, verifyApk(input(pkg = "com.evil.app")))
    }

    @Test fun `same or lower versionCode is not an upgrade`() {
        assertEquals(UpdateError.NOT_AN_UPGRADE, verifyApk(input(apkVc = 4, installedVc = 4)))
        assertEquals(UpdateError.NOT_AN_UPGRADE, verifyApk(input(apkVc = 3, installedVc = 4)))
    }

    @Test fun `different signer fails`() {
        assertEquals(UpdateError.DIFFERENT_SIGNER, verifyApk(input(apkSigners = setOf("deadbeef"))))
        assertEquals(UpdateError.DIFFERENT_SIGNER, verifyApk(input(apkSigners = emptySet())))
    }

    @Test fun `signer match by intersection passes even with extra installed certs`() {
        assertNull(verifyApk(input(apkSigners = setOf("cafebabe"), installedSigners = setOf("cafebabe", "rotated"))))
    }

    @Test fun `checksum is checked before identity`() {
        // A wrong-package APK that also fails checksum reports the checksum first.
        assertEquals(
            UpdateError.CHECKSUM_MISMATCH,
            verifyApk(input(actualSha = "d".repeat(64), pkg = "com.evil.app")),
        )
    }
}

class UpdatePolicyTest {

    private val day = UpdatePolicy.CHECK_INTERVAL_MS

    @Test fun `auto check runs when never checked`() {
        assertTrue(UpdatePolicy.shouldAutoCheck(nowMs = day, lastCheckMs = 0, autoCheckEnabled = true, playerOpen = false))
    }

    @Test fun `auto check waits until 24h elapsed`() {
        val now = 10 * day
        assertFalse(UpdatePolicy.shouldAutoCheck(now, lastCheckMs = now - day + 1, autoCheckEnabled = true, playerOpen = false))
        assertTrue(UpdatePolicy.shouldAutoCheck(now, lastCheckMs = now - day, autoCheckEnabled = true, playerOpen = false))
    }

    @Test fun `auto check never runs while player open`() {
        assertFalse(UpdatePolicy.shouldAutoCheck(10 * day, lastCheckMs = 0, autoCheckEnabled = true, playerOpen = true))
    }

    @Test fun `auto check never runs when disabled`() {
        assertFalse(UpdatePolicy.shouldAutoCheck(10 * day, lastCheckMs = 0, autoCheckEnabled = false, playerOpen = false))
    }

    @Test fun `surface unless exactly that version was skipped`() {
        assertTrue(UpdatePolicy.shouldSurface("0.1.4", skippedVersion = null))
        assertTrue(UpdatePolicy.shouldSurface("0.1.4", skippedVersion = "0.1.3"))
        assertFalse(UpdatePolicy.shouldSurface("0.1.4", skippedVersion = "0.1.4"))
    }
}
