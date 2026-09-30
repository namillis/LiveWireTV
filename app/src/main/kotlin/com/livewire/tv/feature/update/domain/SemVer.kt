package com.livewire.tv.feature.update.domain

/**
 * A parsed semantic version, tolerant of the shapes LiveWire actually ships:
 *
 *  - a leading `v` (`v0.1.4`) is stripped;
 *  - the app's own build suffixes `-debug` and `-dev` are recognised as PRE-RELEASE
 *    markers, so `0.1.4-dev` and `0.1.4-debug` sort BELOW the plain `0.1.4` release;
 *  - any other trailing `-suffix` / `+build` is treated as a pre-release/build tail
 *    and ignored for ordering beyond the release-vs-prerelease rule;
 *  - numeric identifiers compare numerically, so `0.1.10` > `0.1.9`.
 *
 * A tag we cannot parse into at least one numeric component is rejected ([parse]
 * returns null), so a malformed release never looks newer than the installed build.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** True when the version carried a pre-release tail (`-dev`, `-debug`, `-rc1`, ...). */
    val isPreRelease: Boolean,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // Same X.Y.Z: a release (isPreRelease=false) outranks a pre-release.
        val thisRank = if (isPreRelease) 0 else 1
        val otherRank = if (other.isPreRelease) 0 else 1
        return thisRank.compareTo(otherRank)
    }

    companion object {
        /**
         * Parse a version string, or null if it has no usable numeric core.
         * Accepts `v` prefixes and `MAJOR`, `MAJOR.MINOR`, `MAJOR.MINOR.PATCH` cores,
         * with an optional `-prerelease` / `+build` tail.
         */
        fun parse(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            if (s.isEmpty()) return null

            // Split off the pre-release / build tail at the first '-' or '+'.
            val tailIdx = s.indexOfFirst { it == '-' || it == '+' }
            val core = if (tailIdx >= 0) s.substring(0, tailIdx) else s
            // A '+build' with no '-prerelease' is NOT a pre-release; only a '-' tail is.
            val isPre = tailIdx >= 0 && s[tailIdx] == '-'

            val parts = core.split(".")
            if (parts.isEmpty() || parts[0].isBlank()) return null
            val nums = IntArray(3)
            for (i in 0 until 3) {
                val p = parts.getOrNull(i) ?: "0"
                val n = p.toIntOrNull() ?: return null  // non-numeric core component => reject
                if (n < 0) return null
                nums[i] = n
            }
            // Reject trailing garbage in the core beyond three components only if non-numeric.
            for (i in 3 until parts.size) {
                if (parts[i].toIntOrNull() == null) return null
            }
            return SemVer(nums[0], nums[1], nums[2], isPre)
        }
    }
}

/** True when [candidate] represents a strictly newer release than [current]. */
fun isNewerRelease(current: String, candidate: String): Boolean {
    val cur = SemVer.parse(current) ?: return false
    val cand = SemVer.parse(candidate) ?: return false
    return cand > cur
}
