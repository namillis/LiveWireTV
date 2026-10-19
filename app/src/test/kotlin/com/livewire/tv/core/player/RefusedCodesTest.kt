package com.livewire.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusedCodesTest {

    @Test
    fun `auth churn, rate limit and connection limit count as refused`() {
        listOf(401, 403, 429, 456, 460).forEach { assertTrue("$it", RefusedCodes.isRefusal(it)) }
    }

    @Test
    fun `a missing stream or a server error is not a refusal`() {
        listOf(200, 302, 404, 410, 500, 502, 503).forEach { assertFalse("$it", RefusedCodes.isRefusal(it)) }
    }

    @Test
    fun `no HTTP status is not a refusal`() {
        assertFalse(RefusedCodes.isRefusal(null))
    }
}
