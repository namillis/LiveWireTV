package com.livewire.tv.feature.providers.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderConfigCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun configsStoredBeforeM3uSupportDecodeAsXtream() {
        val legacy = """[{"id":"1","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}]"""
        val cfg = json.decodeFromString(ListSerializer(ProviderConfig.serializer()), legacy).single()
        assertEquals(ProviderType.XTREAM, cfg.type)
        assertNull(cfg.epgUrl)
        assertEquals("u", cfg.username)
    }

    @Test
    fun displayHostNeverExposesPathOrQuery() {
        val cfg = ProviderConfig(
            id = "1", name = "P", type = ProviderType.M3U,
            baseUrl = "http://iptv.example:8080/get.php?username=u&password=secret",
        )
        assertEquals("iptv.example", cfg.displayHost())
    }

    @Test
    fun cleartextCoversTheGuideUrlToo() {
        val cfg = ProviderConfig(
            id = "1", name = "P", type = ProviderType.M3U,
            baseUrl = "https://h/p.m3u", epgUrl = "http://g/epg.xml",
        )
        assertEquals(true, cfg.usesCleartextTransport())
    }
}
