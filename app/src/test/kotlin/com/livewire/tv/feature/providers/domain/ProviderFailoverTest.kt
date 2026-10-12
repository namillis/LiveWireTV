package com.livewire.tv.feature.providers.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderFailoverTest {

    private val pool = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv1", password = "x")
    private val teck = ProviderConfig(id = "teck", name = "Teck", baseUrl = "http://teck.example", username = "u", password = "p")
    private val m3u = ProviderConfig(id = "m3u", name = "Playlist", baseUrl = "https://x.example/a.m3u", type = ProviderType.M3U)

    private var now = 1_000_000L
    private var up = true
    private var checks = 0
    private val failover = ProviderFailover(check = { checks++; up }, clock = { now }, upTtlMs = 600_000L, downTtlMs = 120_000L)

    @Test
    fun `no backup means no check and the first provider`() = runTest {
        assertEquals(pool, failover.choose(listOf(pool, teck), backupId = null))
        assertEquals(0, checks)
        assertNull(failover.failover)
    }

    @Test
    fun `an M3U main provider is never checked`() = runTest {
        assertEquals(m3u, failover.choose(listOf(m3u, teck), backupId = "teck"))
        assertEquals(0, checks)
    }

    @Test
    fun `a backup that is also the active provider is ignored`() = runTest {
        assertEquals(pool, failover.choose(listOf(pool, teck), backupId = "pool"))
        assertEquals(0, checks)
    }

    @Test
    fun `the backup takes over while the main provider is down`() = runTest {
        up = false
        assertEquals(teck, failover.choose(listOf(pool, teck), backupId = "teck"))
        assertEquals(Failover(pool, teck), failover.failover)
    }

    @Test
    fun `answers are reused, and the main provider comes back after it recovers`() = runTest {
        val all = listOf(pool, teck)
        assertEquals(pool, failover.choose(all, "teck"))
        now += 300_000L
        assertEquals(pool, failover.choose(all, "teck"))
        assertEquals(1, checks)

        now += 400_000L
        up = false
        assertEquals(teck, failover.choose(all, "teck"))
        assertEquals(2, checks)
        now += 60_000L
        up = true
        assertEquals(teck, failover.choose(all, "teck"), "still within the 2-minute down window")
        now += 61_000L
        assertEquals(pool, failover.choose(all, "teck"))
        assertNull(failover.failover)
        assertEquals(3, checks)
    }

    @Test
    fun `recheck forces a fresh check`() = runTest {
        val all = listOf(pool, teck)
        failover.choose(all, "teck")
        up = false
        failover.recheck()
        assertEquals(teck, failover.choose(all, "teck"))
        assertEquals(2, checks)
    }

    @Test
    fun `a check that throws counts as down`() = runTest {
        val f = ProviderFailover(check = { error("boom") }, clock = { now })
        assertEquals(teck, f.choose(listOf(pool, teck), "teck"))
    }

    @Test
    fun `changing the active provider checks the new one`() = runTest {
        failover.choose(listOf(pool, teck), "teck")
        failover.choose(listOf(teck, pool), "pool")
        assertEquals(2, checks)
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
