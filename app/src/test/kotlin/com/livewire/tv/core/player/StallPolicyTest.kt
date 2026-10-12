package com.livewire.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallPolicyTest {

    @Test
    fun `two switches a minute, then the user decides`() {
        val p = StallPolicy()
        assertTrue(p.allowSwitch(0))
        assertTrue(p.allowSwitch(15_000))
        assertFalse(p.allowSwitch(30_000))
        assertFalse(p.allowSwitch(59_999))
    }

    @Test
    fun `the allowance comes back as old switches age out`() {
        val p = StallPolicy()
        p.allowSwitch(0)
        p.allowSwitch(15_000)
        assertTrue(p.allowSwitch(60_000))
        assertFalse(p.allowSwitch(70_000))
        assertTrue(p.allowSwitch(75_000))
    }

    @Test
    fun `a new channel or a user retry resets it`() {
        val p = StallPolicy()
        p.allowSwitch(0)
        p.allowSwitch(1_000)
        p.reset()
        assertTrue(p.allowSwitch(2_000))
    }
}
