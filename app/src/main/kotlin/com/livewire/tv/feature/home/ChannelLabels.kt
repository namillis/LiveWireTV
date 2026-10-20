package com.livewire.tv.feature.home

import com.livewire.tv.feature.epg.domain.EpgProgramme

/** "36 min left", or "ends soon" in the last minute. */
internal fun minutesLeft(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

/** Text fallback when a channel has no logo: strip "US - " style prefixes and quality tags. */
internal fun wordmark(name: String): String {
    val base = name.substringAfter(" - ", name)
        .replace(Regex("""\b(HD|FHD|UHD|SD|4K|HEVC)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*]"""), "")
        .trim()
    return base.split(' ').filter { it.isNotBlank() }.take(2).joinToString(" ").ifBlank { name.take(6) }
}
