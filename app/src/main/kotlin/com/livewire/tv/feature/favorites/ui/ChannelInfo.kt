package com.livewire.tv.feature.favorites.ui

import com.livewire.tv.feature.settings.data.StreamFormat

/**
 * Pure, cheap channel-info helpers for the Channel info panel. "Cheap sources only" (task §4):
 * the stream FORMAT comes from Settings and the QUALITY is parsed from the channel name — the
 * panel never starts the stream to probe resolution or audio.
 */
object ChannelInfo {

    /** The FORMAT chip label: the user's chosen stream format, short form. */
    fun formatLabel(format: StreamFormat): String = when (format) {
        StreamFormat.TS -> "TS"
        StreamFormat.HLS -> "HLS"
    }

    /**
     * The QUALITY tag parsed from a channel name's quality marker (FHD/HD/UHD/4K/SD), or null
     * when the name carries none. Case-insensitive, whole-word, so "SHDTV" or "4KIDS" never
     * match. 4K and UHD are both reported as "UHD".
     */
    fun qualityLabel(name: String): String? {
        val upper = name.uppercase()
        return QUALITY_ORDER.firstOrNull { marker -> WORD[marker]!!.containsMatchIn(upper) }
            ?.let { if (it == "4K") "UHD" else it }
    }

    // Most specific first so "FHD" wins over "HD" in "US - FOX FHD".
    private val QUALITY_ORDER = listOf("UHD", "4K", "FHD", "HD", "SD")
    private val WORD: Map<String, Regex> = QUALITY_ORDER.associateWith { Regex("""\b${Regex.escape(it)}\b""") }
}
