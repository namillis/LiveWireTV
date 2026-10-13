package com.livewire.tv.feature.providers.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolSignalsTest {

    @Test
    fun `reads the pool's features`() {
        assertEquals(setOf("retry", "stop"), PoolSignals.parseFeatures("""{"pool":1,"version":1,"features":["retry","stop"]}"""))
        assertEquals(setOf("retry"), PoolSignals.parseFeatures("""{"pool":"1","features":["retry",""]}"""))
    }

    @Test
    fun `anything that isn't the pool has no features`() {
        listOf(
            null,
            "",
            "[]",
            "<html>404</html>",
            """{"user_info":{"auth":1}}""",
            """{"user_info":{"auth":0}}""",
            """{"pool":0,"features":["retry"]}""",
            """{"pool":1,"features":"retry"}""",
        ).forEach { assertEquals("for $it", emptySet<String>(), PoolSignals.parseFeatures(it)) }
    }

    @Test
    fun `attempt URLs add try and why`() {
        val url = "https://iptv.example/live/tv1/pass/12.ts"
        assertEquals("$url?try=0", PoolSignals.attemptUrl(url, 0))
        assertEquals("$url?try=2&why=refused", PoolSignals.attemptUrl(url, 2, refused = true))
        assertEquals("https://x.example/a.ts?token=1&try=1", PoolSignals.attemptUrl("https://x.example/a.ts?token=1", 1))
    }

    @Test
    fun `a picked source opens at try 0 with the source key`() {
        assertEquals("https://iptv.example/live/u/p/12.ts?try=0&source=teck.71936", PoolSignals.pickedUrl("https://iptv.example/live/u/p/12.ts", "teck.71936"))
    }

    @Test
    fun `reads the pool's sources in order`() {
        val body = """{"sources":[
            {"source":"teck.1","provider":"Main","name":"US - ESPN HD","quality":"HD","backup":false,"current":true,"failed":false},
            {"source":"nova.2","provider":"Nova","name":"US| ESPN [BK]","quality":"HD","backup":true,"current":false,"failed":true},
            {"provider":"No key"},
            {"source":"x.3"}
        ]}"""
        val sources = PoolSignals.parseSources(body)
        assertEquals(listOf("teck.1", "nova.2", "x.3"), sources.map { it.key })
        assertEquals("Main · HD", sources[0].label)
        assertEquals(true, sources[0].current)
        assertEquals("Nova · HD · Backup", sources[1].label)
        assertEquals(true, sources[1].failed)
        assertEquals("x", sources[2].label)
    }

    @Test
    fun `a bad sources response is an empty list`() {
        listOf(null, "", "[]", "{}", """{"sources":"x"}""", "<html>").forEach {
            assertEquals("for $it", emptyList<PoolSource>(), PoolSignals.parseSources(it))
        }
    }

    private fun event(body: String) = PoolSignals.parseEvent(Json.parseToJsonElement(body), "1126879636")

    @Test
    fun `reads a Live Now event as a programme on the stream`() {
        val p = event(
            """{"title":"NFL · Week 5","description":"Bills (3-1) at Rams (2-2).","category":"Football",
               "start":1791850500,"end":1791862200}""",
        )!!
        assertEquals("1126879636", p.channelId)
        assertEquals(1_791_850_500_000L, p.startMs)
        assertEquals(1_791_862_200_000L, p.stopMs)
        assertEquals("NFL · Week 5", p.title)
        assertEquals("Bills (3-1) at Rams (2-2).", p.description)
        assertEquals("Football", p.category)
        assertTrue(p.endIsEstimate)
    }

    @Test
    fun `an event's optional parts may be missing and times may be strings`() {
        val p = event("""{"title":" College Football ","description":"  ","start":"1791850500","end":1791862200.0}""")!!
        assertEquals("College Football", p.title)
        assertNull(p.description)
        assertNull(p.category)
        assertEquals(1_791_862_200_000L, p.stopMs)
    }

    @Test
    fun `a malformed event is ignored`() {
        listOf(
            """{"start":1791850500,"end":1791862200}""",
            """{"title":"","start":1791850500,"end":1791862200}""",
            """{"title":"NFL","end":1791862200}""",
            """{"title":"NFL","start":1791850500}""",
            """{"title":"NFL","start":1791850500,"end":1791850500}""",
            """{"title":"NFL","start":1791862200,"end":1791850500}""",
            """{"title":"NFL","start":"soon","end":1791862200}""",
            """{"title":"NFL","start":0,"end":1791862200}""",
            """{"title":null,"start":1791850500,"end":1791862200}""",
            "\"NFL\"",
            "[]",
            "null",
        ).forEach { assertNull("for $it", event(it)) }
        assertNull(PoolSignals.parseEvent(null, "1"))
    }

    @Test
    fun `events are kept only when the pool lists event_info`() {
        val game = LiveChannel(
            streamId = "1", name = "Bills @ Rams", categoryId = "187",
            poolEvent = event("""{"title":"NFL","start":1791850500,"end":1791862200}"""),
        )
        val plain = LiveChannel(streamId = "2", name = "Sky Sports", categoryId = "3")
        val channels = listOf(game, plain)

        assertSame(channels, PoolSignals.withEvents(channels, setOf("retry", PoolSignals.EVENT_INFO)))
        assertEquals(listOf(game.copy(poolEvent = null), plain), PoolSignals.withEvents(channels, setOf("retry", "stop", "sources")))
        assertEquals(listOf(game.copy(poolEvent = null), plain), PoolSignals.withEvents(channels, emptySet()))
        // Nothing to drop: the same list comes back, so a plain panel pays no copy.
        val none = listOf(plain)
        assertSame(none, PoolSignals.withEvents(none, emptySet()))
    }

    @Test
    fun `event_info is read like any other feature`() {
        assertTrue(PoolSignals.EVENT_INFO in PoolSignals.parseFeatures("""{"pool":1,"version":1,"features":["retry","stop","sources","event_info"]}"""))
    }
}
