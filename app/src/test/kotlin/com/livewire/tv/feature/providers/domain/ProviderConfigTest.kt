package com.livewire.tv.feature.providers.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {

    private val cfg = ProviderConfig(
        id = "1", name = "p", baseUrl = "http://host:8080", username = "user", password = "pa ss",
    )

    @Test
    fun `player api url includes credentials`() {
        val url = cfg.playerApiUrl()
        assertTrue(url.startsWith("http://host:8080/player_api.php?"))
        assertTrue(url.contains("username=user"))
        assertTrue(url.contains("password=pa%20ss")) // space encoded
    }

    @Test
    fun `player api url includes action and extra params`() {
        val url = cfg.playerApiUrl(action = "get_live_streams", extra = mapOf("category_id" to "5"))
        assertTrue(url.contains("action=get_live_streams"))
        assertTrue(url.contains("category_id=5"))
    }

    @Test
    fun `live stream url format`() {
        assertEquals(
            "http://host:8080/live/user/pa ss/42.ts",
            cfg.liveStreamUrl("42"),
        )
    }
}
