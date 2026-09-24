package com.livewire.tv.feature.providers.data

import java.io.BufferedReader
import java.io.Reader

/** One playable entry from an M3U playlist. [url] is security-sensitive. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class M3uPlaylist(
    /** First guide URL advertised by the `#EXTM3U` header (`url-tvg` / `x-tvg-url`). */
    val epgUrl: String?,
    val entries: List<M3uEntry>,
    /** True when [M3uParser.MAX_ENTRIES] cut the playlist short. */
    val truncated: Boolean = false,
)

/**
 * Line-streaming parser for extended M3U (`#EXTM3U` / `#EXTINF`). It reads one line
 * at a time and never holds the raw playlist text, so large provider playlists stay
 * within a low-memory TV budget.
 *
 * Supported: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTGRP`, and
 * per-entry `#EXTVLCOPT:http-user-agent` / `http-referrer`. Xtream-generated
 * playlists mix in VOD, so `/movie/` and `/series/` entries are skipped: LiveWire's
 * M3U support covers live channels.
 */
object M3uParser {

    const val MAX_ENTRIES = 30_000

    private val attribute = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val vodPath = Regex("""/(movie|series)/""", RegexOption.IGNORE_CASE)

    fun parse(reader: Reader, maxEntries: Int = MAX_ENTRIES): M3uPlaylist {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        var epgUrl: String? = null
        val entries = ArrayList<M3uEntry>()
        var truncated = false

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()

        fun resetPending() {
            pendingInfo = null
            pendingGroup = null
            pendingHeaders.clear()
        }

        var firstLine = true
        while (true) {
            val raw = buffered.readLine() ?: break
            val line = if (firstLine) raw.removePrefix("\uFEFF").trim() else raw.trim()
            firstLine = false
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    if (epgUrl == null) epgUrl = headerGuideUrl(line)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    resetPending()
                    pendingInfo = line
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        }
                    }
                }
                line.startsWith("#") -> Unit // other directives are ignored
                else -> {
                    val info = pendingInfo
                    if (info != null && !vodPath.containsMatchIn(line)) {
                        if (entries.size >= maxEntries) {
                            truncated = true
                            break
                        }
                        entries += toEntry(info, line, pendingGroup, pendingHeaders.toMap())
                    }
                    resetPending()
                }
            }
        }
        return M3uPlaylist(epgUrl = epgUrl, entries = entries, truncated = truncated)
    }

    private fun headerGuideUrl(line: String): String? {
        val attrs = attributes(line)
        val value = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: return null
        // Some playlists list several guides separated by commas; use the first.
        return value.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun toEntry(
        info: String,
        url: String,
        extGroup: String?,
        headers: Map<String, String>,
    ): M3uEntry {
        val attrs = attributes(info)
        val displayName = displayName(info)
        val name = displayName.ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { "Unnamed channel" }
        return M3uEntry(
            name = name,
            url = url,
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            logoUrl = attrs["tvg-logo"]?.trim()?.ifEmpty { null },
            group = attrs["group-title"]?.primaryGroup() ?: extGroup?.primaryGroup(),
            headers = headers,
        )
    }

    private fun attributes(line: String): Map<String, String> =
        attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /**
     * Some playlists (iptv-org, for example) list several groups as `Animation;Kids`.
     * A channel appears in one rail, so the first group wins.
     */
    private fun String.primaryGroup(): String? =
        split(';').map { it.trim() }.firstOrNull { it.isNotEmpty() }

    /** The title follows the first comma that is outside a quoted attribute value. */
    private fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return info.substring(i + 1).trim()
            }
        }
        return ""
    }
}
