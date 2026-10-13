package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeChannelsTest {
    private val sky = "nova.skysportsmainevent.uk"
    private val event = EpgProgramme("99", 1L, 2L, "NFL · Week 5", endIsEstimate = true)
    private val game = LiveChannel(streamId = "99", name = "Bills @ Rams", epgChannelId = sky, categoryId = "187", poolEvent = event)
    private val skyMain = LiveChannel(streamId = "5", name = "Sky Sports Main Event", epgChannelId = sky, categoryId = "3")

    @Test
    fun `the real channel holds its guide id wherever the game is listed`() {
        assertEquals("5", ProgrammeChannels.byGuideId(listOf(game, skyMain))[sky]?.streamId)
        assertEquals("5", ProgrammeChannels.byGuideId(listOf(skyMain, game))[sky]?.streamId)
    }

    @Test
    fun `a game holds the id when no real channel has it`() {
        val other = game.copy(streamId = "100", name = "Jets @ Bills")
        assertEquals("100", ProgrammeChannels.byGuideId(listOf(game, other))[sky]?.streamId)
    }

    @Test
    fun `ordinary duplicates keep the last one listed, as before`() {
        val hd = skyMain.copy(streamId = "6", name = "Sky Sports Main Event HD")
        val owners = ProgrammeChannels.byGuideId(listOf(skyMain, hd, LiveChannel(streamId = "8", name = "No guide", categoryId = "1")))
        assertEquals("6", owners[sky]?.streamId)
        assertEquals(1, owners.size)
    }
}
