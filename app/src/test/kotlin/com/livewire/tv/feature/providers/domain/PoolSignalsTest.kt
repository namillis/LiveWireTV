package com.livewire.tv.feature.providers.domain

import org.junit.Assert.assertEquals
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
}
