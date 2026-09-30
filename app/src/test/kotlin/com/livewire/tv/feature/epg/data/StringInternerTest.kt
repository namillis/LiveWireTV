package com.livewire.tv.feature.epg.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StringInternerTest {

    @Test fun `returns same instance for equal values`() {
        val interner = StringInterner()
        // Distinct String objects with equal content (avoid the compile-time constant pool).
        val a = StringBuilder("news").toString()
        val b = StringBuilder("news").toString()
        assertNotSame("precondition: a and b are distinct instances", a, b)

        val ia = interner.internNonNull(a)
        val ib = interner.internNonNull(b)
        assertEquals("news", ia)
        assertSame("equal values collapse to one instance", ia, ib)
        assertSame("first interned instance is the canonical one", a, ia)
    }

    @Test fun `distinct values stay distinct`() {
        val interner = StringInterner()
        assertEquals("sports", interner.internNonNull("sports"))
        assertEquals("news", interner.internNonNull("news"))
    }

    @Test fun `null passes through`() {
        assertNull(StringInterner().intern(null))
    }
}
