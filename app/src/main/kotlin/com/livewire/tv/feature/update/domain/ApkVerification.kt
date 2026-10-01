package com.livewire.tv.feature.update.domain

/**
 * The facts read from a downloaded APK plus the installed app, reduced to what the
 * verification decision needs. Kept as a plain data class so the decision function is a
 * pure, unit-testable transform independent of Android's PackageManager.
 */
data class ApkVerificationInput(
    /** Expected SHA-256 from the release's `.sha256` sidecar (lower-case hex), or null if absent. */
    val expectedSha256: String?,
    /** SHA-256 computed over the downloaded file (lower-case hex). */
    val actualSha256: String,
    /** Expected byte size from the release asset. */
    val expectedSize: Long,
    /** Actual size of the downloaded file. */
    val actualSize: Long,
    /** Package name read from the downloaded APK (null if unreadable). */
    val apkPackageName: String?,
    /** versionCode read from the downloaded APK (null if unreadable). */
    val apkVersionCode: Long?,
    /** Signing-cert SHA-256 digests found in the downloaded APK (lower-case hex). */
    val apkSignerDigests: Set<String>,
    /** The installed app's package name. */
    val installedPackageName: String,
    /** The installed app's versionCode. */
    val installedVersionCode: Long,
    /** The installed app's signing-cert SHA-256 digests (lower-case hex). */
    val installedSignerDigests: Set<String>,
)

/**
 * Verify a downloaded APK before handing it to the installer. Returns null when every
 * check passes, or the first [UpdateError] that fails. Order matters: size and checksum
 * first (cheap, catches corruption/tampering), then identity (package, upgrade, signer).
 *
 * A missing expected checksum is treated as a failure, not a pass — we never install an
 * unverified download.
 */
fun verifyApk(input: ApkVerificationInput): UpdateError? {
    if (input.expectedSize > 0 && input.actualSize != input.expectedSize) {
        return UpdateError.CHECKSUM_MISMATCH
    }
    val expected = input.expectedSha256
    if (expected.isNullOrBlank() || !expected.equals(input.actualSha256, ignoreCase = true)) {
        return UpdateError.CHECKSUM_MISMATCH
    }
    if (input.apkPackageName == null || input.apkVersionCode == null) {
        return UpdateError.CORRUPT_APK
    }
    if (input.apkPackageName != input.installedPackageName) {
        return UpdateError.WRONG_PACKAGE
    }
    if (input.apkVersionCode <= input.installedVersionCode) {
        return UpdateError.NOT_AN_UPGRADE
    }
    // Signer must match: the downloaded APK's cert set must intersect the installed set.
    if (input.apkSignerDigests.isEmpty() ||
        input.apkSignerDigests.intersect(input.installedSignerDigests).isEmpty()
    ) {
        return UpdateError.DIFFERENT_SIGNER
    }
    return null
}
