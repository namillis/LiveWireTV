package com.livewire.tv.feature.sports.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class EspnScheduleTest {
    private fun root(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun board(vararg states: String) = root(
        """{"week":{"number":10},"season":{"type":2,"year":2026},"events":[""" +
            states.joinToString(",") { """{"competitions":[{"status":{"type":{"state":"$it"}}}]}""" } +
            "]}",
    )

    @Test fun `asks for today and the next two days in Eastern time`() {
        // 2026-11-17T14:52Z = Tue 17 Nov 2026, 9:52 AM EST.
        assertEquals(
            listOf("dates=20261117", "dates=20261118", "dates=20261119"),
            EspnSchedule.dayQueries(Instant.parse("2026-11-17T14:52:00Z")),
        )
    }

    @Test fun `late evening Eastern is still that day`() {
        // 2026-11-18T03:30Z = Tue 17 Nov 2026, 10:30 PM EST.
        assertEquals("dates=20261117", EspnSchedule.dayQueries(Instant.parse("2026-11-18T03:30:00Z")).first())
    }

    @Test fun `asks for the next week once every game is over`() {
        assertEquals("week=11&seasontype=2", EspnSchedule.nextWeekQuery(board("post", "post")))
    }

    @Test fun `keeps the current week while a game is left`() {
        assertNull(EspnSchedule.nextWeekQuery(board("post", "in")))
        assertNull(EspnSchedule.nextWeekQuery(board("pre")))
    }

    @Test fun `keeps the default when there is no week or no games`() {
        assertNull(EspnSchedule.nextWeekQuery(board()))
        assertNull(EspnSchedule.nextWeekQuery(root("""{"events":[{"status":{"type":{"state":"post"}}}]}""")))
        assertNull(EspnSchedule.nextWeekQuery(root("{}")))
    }
}
