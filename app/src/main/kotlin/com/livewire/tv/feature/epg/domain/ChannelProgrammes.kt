package com.livewire.tv.feature.epg.domain

import com.livewire.tv.feature.providers.domain.LiveChannel

/**
 * What a channel is showing, for every screen that names the current or next programme. A pool
 * Live Now channel ([LiveChannel.poolEvent]) borrows a real channel's guide id, and that guide
 * often describes something else (a postgame show, another language), so a channel with an event
 * shows the event alone and never the borrowed guide. Any other channel reads the guide as before.
 *
 * [guide] looks up a guide id's programmes, in any order; screens pass their own loaded guide.
 */
object ChannelProgrammes {

    /** [channel]'s listings: its event alone when it has one, else the guide's programmes for it. */
    fun listings(channel: LiveChannel, guide: (String) -> List<EpgProgramme>): List<EpgProgramme> =
        channel.poolEvent?.let(::listOf) ?: channel.epgChannelId?.let(guide).orEmpty()

    /**
     * What [channel] shows as on now at [at]. An event fills this slot for as long as the channel
     * is listed: before its start (screens show "Starts 8:15 PM", see [EventTimes]) and past its
     * end, which is only an estimate. The pool drops the channel once the event is over.
     */
    fun now(channel: LiveChannel, at: Long, guide: (String) -> List<EpgProgramme>): EpgProgramme? =
        channel.poolEvent ?: listings(channel, guide).firstOrNull { it.airsAt(at) }

    /** The programme after the one on now; none for an event channel, which lists only the event. */
    fun next(channel: LiveChannel, at: Long, guide: (String) -> List<EpgProgramme>): EpgProgramme? =
        later(channel, at, guide).firstOrNull()

    /** Programmes starting after [at], soonest first; empty for an event channel. */
    fun later(channel: LiveChannel, at: Long, guide: (String) -> List<EpgProgramme>): List<EpgProgramme> =
        if (channel.poolEvent != null) emptyList()
        else listings(channel, guide).filter { it.startMs > at }.sortedBy { it.startMs }

    /** [guide]'s programmes for a guide id, for screens holding an [EpgGuide] (null while loading). */
    fun from(guide: EpgGuide?): (String) -> List<EpgProgramme> = { id -> guide?.programmesFor(id).orEmpty() }
}
