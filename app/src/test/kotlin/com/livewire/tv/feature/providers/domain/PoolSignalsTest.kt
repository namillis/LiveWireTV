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
}
