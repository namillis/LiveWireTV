package com.livewire.tv.feature.update.domain

/**
 * A GitHub release reduced to what the updater needs. Parsed from the releases API by
 * [com.livewire.tv.feature.update.data.GitHubReleaseSource]; [assets] carry the APK and
 * its `.sha256` sidecar.
 */
data class ReleaseInfo(
    val tagName: String,
    val name: String?,
    /** Release notes (markdown), shown as plain text. */
    val notes: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val publishedAtEpochMs: Long?,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/** The version string without a leading `v`, for display and comparison. */
val ReleaseInfo.version: String get() = tagName.removePrefix("v").removePrefix("V")

/**
 * Result of picking the installable APK (and its checksum sidecar) out of a release's
 * assets. [sha256Asset] is null when the release published no `.sha256` sidecar; the
 * caller decides whether to proceed without checksum verification (it should not).
 */
data class SelectedAssets(
    val apk: ReleaseAsset,
    val sha256Asset: ReleaseAsset?,
)

/**
 * Choose the APK asset for [versionName] and its matching `.sha256` sidecar.
 *
 * Preference order for the APK:
 *  1. the exact expected name from the pattern (e.g. `livewire-0.1.4.apk`),
 *  2. otherwise the single `.apk` asset if there is exactly one,
 *  3. otherwise null (ambiguous — don't guess).
 *
 * The sidecar is `<apkName>.sha256` when present.
 */
fun selectAssets(
    assets: List<ReleaseAsset>,
    versionName: String,
    assetPattern: String,
): SelectedAssets? {
    val expected = runCatching { String.format(assetPattern, versionName) }.getOrNull()
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val apk = when {
        expected != null && apks.any { it.name == expected } -> apks.first { it.name == expected }
        apks.size == 1 -> apks.single()
        else -> return null
    }
    val sidecarName = apk.name + ".sha256"
    val sidecar = assets.firstOrNull { it.name == sidecarName }
    return SelectedAssets(apk, sidecar)
}

/**
 * Parse the SHA-256 hex digest out of a `sha256sum`-format sidecar. That format is
 * `<64-hex>  <filename>` (two spaces), but tolerate a single space, a leading `*`
 * binary marker, extra whitespace, and a bare digest with no filename. Returns the
 * lower-cased 64-char hex digest, or null if none is found.
 */
fun parseSha256(content: String?): String? {
    if (content.isNullOrBlank()) return null
    for (line in content.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        // First whitespace-delimited token is the digest in sha256sum format.
        val token = trimmed.split(Regex("\\s+")).firstOrNull()?.removePrefix("*") ?: continue
        val lower = token.lowercase()
        if (lower.length == 64 && lower.all { it in '0'..'9' || it in 'a'..'f' }) return lower
    }
    return null
}

/** Typed, user-facing failure reasons. [message] is plain-language, ready to show. */
enum class UpdateError(val message: String) {
    NO_CONNECTION("Couldn't reach the internet. Check your connection and try again."),
    GITHUB_UNAVAILABLE("Couldn't reach GitHub to check for updates. Try again later."),
    CHECKSUM_MISMATCH("The download didn't match what was expected and was discarded."),
    UNKNOWN_SOURCES_NOT_ALLOWED("LiveWire needs permission to install apps. Allow it and try again."),
    NOT_ENOUGH_SPACE("Not enough free space to download the update."),
    DIFFERENT_SIGNER("The update was signed by a different developer and can't be installed."),
    WRONG_PACKAGE("The downloaded file isn't a LiveWire update."),
    NOT_AN_UPGRADE("The downloaded file isn't newer than the installed version."),
    CORRUPT_APK("The downloaded file couldn't be read as an app."),
    INSTALL_FAILED("The update couldn't be installed. Try again."),
    INSTALL_ABORTED("The update was cancelled."),
    UNKNOWN("Something went wrong with the update."),
}

/**
 * Map a network-layer throwable to a user-facing [UpdateError]. Pure and unit-testable;
 * shared by the checker and the downloader so a "no internet" failure reads the same
 * everywhere. Unknown-host / connection-refused is a connectivity problem; a timeout or
 * any other IO problem is reported as GitHub being unavailable.
 */
fun mapNetworkError(cause: Throwable?): UpdateError = when (cause) {
    is java.net.UnknownHostException, is java.net.ConnectException -> UpdateError.NO_CONNECTION
    else -> UpdateError.GITHUB_UNAVAILABLE
}
