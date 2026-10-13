package com.livewire.tv.feature.providers.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverWatchTest {

    private val pool = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv1", password = "x")
    private val teck = ProviderConfig(id = "teck", name = "Teck", baseUrl = "http://teck.example", username = "u", password = "p")

    @Test
    fun `returns the main provider on the first poll after it is back`() = runTest {
        var answer = teck
        var asks = 0
        val watch = async { FailoverWatch.awaitChange(teck, everyMs = 30_000L) { asks++; answer } }

        advanceTimeBy(90_001L)
        runCurrent()
        assertFalse(watch.isCompleted)
        assertEquals(3, asks)

        answer = pool
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(watch.isCompleted)
        assertEquals(pool, watch.await())
        assertEquals(4, asks)
    }

    @Test
    fun `does not ask before the first interval`() = runTest {
        var asks = 0
        val watch = async { FailoverWatch.awaitChange(teck, everyMs = 30_000L) { asks++; pool } }
        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals(0, asks)
        assertEquals(pool, watch.await())
        assertEquals(1, asks)
    }

    @Test
    fun `the same provider under a new object is not a change`() = runTest {
        var asks = 0
        val watch = async {
            FailoverWatch.awaitChange(teck, everyMs = 1_000L) { asks++; if (asks < 3) teck.copy(name = "Teck renamed") else pool }
        }
        assertEquals(pool, watch.await())
        assertEquals(3, asks)
    }

    @Test
    fun `returns null when no provider is configured any more`() = runTest {
        assertNull(FailoverWatch.awaitChange(teck, everyMs = 1_000L) { null })
    }
}
