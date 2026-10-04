package com.livewire.tv.feature.catchup.data

import com.livewire.tv.feature.catchup.domain.PastProgramme
import com.livewire.tv.feature.providers.data.XtreamClient
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Past programme listings for catch-up, fetched on demand and held in memory only.
 *
 * Xtream: `get_simple_data_table` per channel, cached ~15 minutes so scrolling the Guide
 * left doesn't re-hit the panel. Nothing is written to the shared EPG cache, so this keeps
 * the guide's memory footprint flat (the decision recorded in the plan).
 *
 * M3U: no per-channel past-data API exists, and re-parsing the whole XMLTV file per channel
 * is too slow for a TV stick, so this returns an EMPTY past listing for M3U. M3U catch-up
 * URL building still works ([com.livewire.tv.feature.catchup.domain.ArchiveUrlBuilder]); only
 * the past-programme LIST is unavailable, which the UI phase surfaces as "no past guide data".
 */
@Singleton
class ArchiveRepository @Inject constructor(
    private val xtream: XtreamClient,
) {
    private data class Key(val providerId: String, val streamId: String)
    private data class Entry(val fetchedAt: Long, val listings: List<PastProgramme>)

    private val mutex = Mutex()
    private val cache = HashMap<Key, Entry>()

    /**
     * Past programmes on [channel] overlapping [fromMs, toMs) (UTC epoch millis), each tagged
     * with whether the provider actually has it archived. Empty on error or for M3U.
     */
    suspend fun pastListings(
        cfg: ProviderConfig,
        channel: LiveChannel,
        fromMs: Long,
        toMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): List<PastProgramme> {
        if (!channel.hasArchive) return emptyList()
        if (cfg.type != ProviderType.XTREAM) return emptyList()

        val key = Key(cfg.id, channel.streamId)
        val all = mutex.withLock {
            val cached = cache[key]
            if (cached != null && nowMs - cached.fetchedAt < CACHE_TTL_MS) {
                cached.listings
            } else {
                val fetched = xtream.simpleDataTable(cfg, channel.streamId).map {
                    PastProgramme(
                        startMs = it.startMs,
                        stopMs = it.stopMs,
                        title = it.title,
                        description = it.description,
                        hasArchive = it.hasArchive,
                    )
                }
                cache[key] = Entry(nowMs, fetched)
                fetched
            }
        }
        return all.filter { it.stopMs > fromMs && it.startMs < toMs }
    }

    /** Drop everything (for example after a provider is deleted). */
    suspend fun clear() = mutex.withLock { cache.clear() }

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
