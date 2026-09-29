package com.livewire.tv.feature.providers.domain

/**
 * Shared rules about provider channel names, so every screen agrees on what is a real
 * channel and what is provider chrome. Providers pad their live lists with category
 * dividers ("##### US - NEWS #####", "===== SPORTS =====", "#####", "____") that are
 * not playable channels. [ProviderRepository] filters these out at the one point where
 * live channels are produced, so Home rails, the Home hero, Guide rows, the Sports
 * picker and Search all stop seeing them.
 */
object ChannelNames {

    /**
     * True when [name] is a separator/header placeholder row rather than a playable
     * channel. Two signals catch these without touching real names ("US - CNN HD",
     * "US - FOX 26 HOUSTON HD", "US|NBC CHICAGO", "#1 Hits"):
     *  - the name starts or ends with a run of 2+ marker chars ('#', '=', '*', '_'),
     *    which frames a divider even when it wraps a label; or
     *  - the name has no letter or digit at all.
     *
     * A lone leading marker followed by a real label ("#1 Hits") is NOT a placeholder:
     * a single '#' is not a run, and the name carries letters and digits.
     */
    fun isPlaceholder(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.none { it.isLetterOrDigit() }) return true
        return EDGE_MARKER_RUN.containsMatchIn(trimmed)
    }

    /** A run of 2+ divider chars at the start or end of a name marks a separator row. */
    private val EDGE_MARKER_RUN = Regex("^[#=*_]{2,}|[#=*_]{2,}$")
}
