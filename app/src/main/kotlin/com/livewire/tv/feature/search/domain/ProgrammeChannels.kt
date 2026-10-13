package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.providers.domain.LiveChannel

/** Which channel a guide programme belongs to, for Search's programme results. */
object ProgrammeChannels {

    /**
     * Each guide id's channel. A pool Live Now channel borrows the guide id of the real channel
     * airing its game, so a channel without an event always holds the id: a Search hit for a Sky
     * Sports programme opens Sky Sports, not the game. Among channels of the same kind the last
     * one listed wins, as before.
     */
    fun byGuideId(channels: List<LiveChannel>): Map<String, LiveChannel> {
        val owners = HashMap<String, LiveChannel>()
        for (channel in channels) {
            val id = channel.epgChannelId?.takeIf { it.isNotBlank() } ?: continue
            val held = owners[id]
            if (held == null || held.poolEvent != null || channel.poolEvent == null) owners[id] = channel
        }
        return owners
    }
}
