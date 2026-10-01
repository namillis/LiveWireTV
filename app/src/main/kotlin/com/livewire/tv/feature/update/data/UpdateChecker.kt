package com.livewire.tv.feature.update.data

import com.livewire.tv.BuildConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import com.livewire.tv.feature.update.domain.ReleaseInfo
import com.livewire.tv.feature.update.domain.UpdateError
import com.livewire.tv.feature.update.domain.UpdatePolicy
import com.livewire.tv.feature.update.domain.isNewerRelease
import com.livewire.tv.feature.update.domain.version
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a check. */
sealed interface UpdateCheckResult {
    /** An installable, newer, non-skipped release is available. */
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    /** Installed build is current (or newer). [currentVersion] for the "you're up to date" line. */
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    /** The auto-check policy said "not now" (too soon, disabled, or player open). Nothing fetched. */
    data object Skipped : UpdateCheckResult
    /** A newer release exists but the user chose to skip exactly that version. */
    data class SkippedByUser(val version: String) : UpdateCheckResult
    data class Failed(val error: UpdateError) : UpdateCheckResult
}

/**
 * Decides whether an update should be offered. Wraps [GitHubReleaseSource] with the
 * newness comparison against [BuildConfig.VERSION_NAME], the user's skip choice, and the
 * 24h / auto-check / player-open policy. Records `lastUpdateCheck` after any real fetch.
 *
 * [autoCheck] is the launch path (honours the 24h gate and the auto-check switch);
 * [checkNow] is the manual "Check for updates" path (always fetches).
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val source: GitHubReleaseSource,
    private val settings: SettingsStore,
) {
    /** Automatic launch check. Returns [UpdateCheckResult.Skipped] when policy defers it. */
    suspend fun autoCheck(nowMs: Long, playerOpen: Boolean): UpdateCheckResult {
        val s = settings.settings.first()
        if (!UpdatePolicy.shouldAutoCheck(nowMs, s.lastUpdateCheck, s.autoCheckUpdates, playerOpen)) {
            return UpdateCheckResult.Skipped
        }
        return checkNow(nowMs, s.skippedVersion)
    }

    /**
     * Manual check: always fetches. [skippedVersion] is read from settings when not passed.
     * Records the check time on any completed fetch (success or "up to date"), so a manual
     * check also resets the 24h clock.
     */
    suspend fun checkNow(nowMs: Long, skippedVersion: String? = null): UpdateCheckResult {
        val skip = skippedVersion ?: settings.settings.first().skippedVersion
        return when (val r = source.fetchLatest()) {
            is GitHubReleaseSource.Result.Failed -> UpdateCheckResult.Failed(classify(r.cause))
            is GitHubReleaseSource.Result.Ignored -> {
                settings.setLastUpdateCheck(nowMs)
                UpdateCheckResult.UpToDate(currentVersion())
            }
            is GitHubReleaseSource.Result.NotModified -> {
                settings.setLastUpdateCheck(nowMs)
                r.release?.let { evaluate(it, skip) } ?: UpdateCheckResult.UpToDate(currentVersion())
            }
            is GitHubReleaseSource.Result.Success -> {
                settings.setLastUpdateCheck(nowMs)
                evaluate(r.release, skip)
            }
        }
    }

    private fun evaluate(release: ReleaseInfo, skippedVersion: String?): UpdateCheckResult {
        val current = currentVersion()
        if (!isNewerRelease(current, release.version)) return UpdateCheckResult.UpToDate(current)
        if (!UpdatePolicy.shouldSurface(release.version, skippedVersion)) {
            return UpdateCheckResult.SkippedByUser(release.version)
        }
        return UpdateCheckResult.Available(release)
    }

    /** The installed build's release version, with the app's own `-debug` suffix stripped. */
    private fun currentVersion(): String =
        BuildConfig.VERSION_NAME.removeSuffix("-debug")

    private fun classify(cause: Throwable?): UpdateError = com.livewire.tv.feature.update.domain.mapNetworkError(cause)
}
