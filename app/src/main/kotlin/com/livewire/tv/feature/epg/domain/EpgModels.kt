package com.livewire.tv.feature.epg.domain

/**
 * EPG domain models, populated from an XMLTV feed (Xtream exposes one at xmltv.php).
 * Kotlin port of the Flutter epg_models.dart. All times are epoch millis (UTC).
 */
data class EpgChannel(
    val id: String,          // XMLTV <channel id>; live streams reference it via epgChannelId
    val displayName: String,
    val iconUrl: String? = null,
)

data class EpgProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
) {
    fun airsAt(t: Long): Boolean = t in startMs until stopMs

    /** Progress through the programme at [now], clamped 0..1. */
    fun progressAt(now: Long): Float {
        val total = stopMs - startMs
        if (total <= 0) return 0f
        return ((now - startMs).toFloat() / total).coerceIn(0f, 1f)
    }
}

/** Parsed guide: channels + programmes indexed by channel id for O(1) lookup. */
class EpgGuide(
    val channels: List<EpgChannel>,
    programmesByChannel: Map<String, List<EpgProgramme>>,
) {
    // Ensure each channel's programmes are start-ordered for now/next queries.
    private val byChannel: Map<String, List<EpgProgramme>> =
        programmesByChannel.mapValues { (_, list) -> list.sortedBy { it.startMs } }

    fun programmesFor(channelId: String): List<EpgProgramme> = byChannel[channelId] ?: emptyList()

    /** The programme airing on [channelId] at [at] (default now), or null. */
    fun nowPlaying(channelId: String, at: Long = System.currentTimeMillis()): EpgProgramme? {
        for (p in programmesFor(channelId)) {
            if (p.airsAt(at)) return p
            if (p.startMs > at) break // sorted; nothing later can match
        }
        return null
    }

    /** The next programme after [at] (default now) on [channelId], or null. */
    fun upNext(channelId: String, at: Long = System.currentTimeMillis()): EpgProgramme? {
        for (p in programmesFor(channelId)) {
            if (p.startMs > at) return p
        }
        return null
    }

    val programmeCount: Int get() = byChannel.values.sumOf { it.size }
}
