package com.livewire.tv.feature.home

import androidx.compose.ui.graphics.Color
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.ui.theme.LiveWireBrandColors
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure logic behind the Home / Guide hero band (design system section 9.1, 3.2).
 * No Android/Compose framework calls here so it stays unit-testable. Colour VALUES live
 * in LiveWireColors/LiveWireBrandColors (Theme.kt); this file only maps names to them.
 */

/** Neutral fallback tint for channels not in the brand map (section 3.2). */
val NeutralBrand: Color = LiveWireBrandColors.Neutral

/**
 * A small, fixed brand lookup keyed by a normalised channel name (section 3.2). Kept
 * deliberately short — no palette extraction on the main thread. The colour values come
 * from [LiveWireBrandColors] so no hex lives outside Theme.kt; unknown channels fall back
 * to [NeutralBrand].
 */
private val BRAND_COLORS: List<Pair<String, Color>> = listOf(
    "CNN" to LiveWireBrandColors.Cnn,
    "FOX NEWS" to LiveWireBrandColors.FoxNews,
    "FOX" to LiveWireBrandColors.Fox,
    "ESPN" to LiveWireBrandColors.Espn,
    "BBC" to LiveWireBrandColors.Bbc,
    "NBC" to LiveWireBrandColors.Nbc,
    "CBS" to LiveWireBrandColors.Cbs,
    "ABC" to LiveWireBrandColors.Abc,
    "SKY" to LiveWireBrandColors.Sky,
    "HBO" to LiveWireBrandColors.Hbo,
    "TNT" to LiveWireBrandColors.Tnt,
    "DISCOVERY" to LiveWireBrandColors.Discovery,
    "NATIONAL GEOGRAPHIC" to LiveWireBrandColors.NatGeo,
    "NAT GEO" to LiveWireBrandColors.NatGeo,
    "AMC" to LiveWireBrandColors.Amc,
    "USA" to LiveWireBrandColors.Usa,
    "WEATHER" to LiveWireBrandColors.Weather,
)

/**
 * Brand tint for [channelName]. Normalises the name (strips region/quality tags and
 * punctuation) then matches the longest brand key contained in it, so "US - FOX NEWS
 * CHANNEL HD" maps to FOX NEWS, not FOX. Never touches a bitmap; O(brand map size).
 */
fun brandTint(channelName: String): Color {
    val norm = normaliseBrandKey(channelName)
    // Longest key first so "FOX NEWS" wins over "FOX".
    return BRAND_COLORS
        .sortedByDescending { it.first.length }
        .firstOrNull { (key, _) -> norm.contains(key) }
        ?.second
        ?: NeutralBrand
}

internal fun normaliseBrandKey(name: String): String =
    name.uppercase(Locale.ROOT)
        .replace(Regex("""\b(HD|FHD|UHD|SD|4K|HEVC|CHANNEL|TV|CH \d+)\b"""), " ")
        .replace(Regex("""[^A-Z0-9 ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

/** "36 min left", "ends soon" in the last minute, or "" when nothing is on. */
fun minutesLeftLabel(programme: EpgProgramme?, now: Long): String {
    if (programme == null) return ""
    val mins = ((programme.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

/** "24 min elapsed" (whole minutes since start, clamped at 0). */
fun minutesElapsedLabel(programme: EpgProgramme?, now: Long): String {
    if (programme == null) return ""
    val mins = ((now - programme.startMs) / 60_000L).coerceAtLeast(0)
    return "$mins min elapsed"
}

/**
 * Whole-minute duration of [programme], for the hero "N min" readouts and the guide
 * on-now bar. Zero when the programme is null or malformed.
 */
fun durationMinutes(programme: EpgProgramme?): Long {
    if (programme == null) return 0
    return (TimeUnit.MILLISECONDS.toMinutes(programme.stopMs - programme.startMs)).coerceAtLeast(0)
}
