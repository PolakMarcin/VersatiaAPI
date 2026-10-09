package com.github.marcoral.versatia.util.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationExtensionsTest {
    @Test
    fun `validators return the receiver so they can be chained`() {
        assertEquals(5, 5.validateAtLeast(1).validateAtMost(10).validateNotZero())
        assertEquals(2.5, 2.5.validateBetween(2.0, 3.0))
        assertEquals("b", "b".validateGreaterThan("a").validateLessThan("c"))
        assertEquals(0L, 0L.validateZero())
        assertEquals(7, 7.validateIn(1..10))
    }

    @Test
    fun `failures carry the default message`() {
        val exception = assertFailsWith<IllegalArgumentException> { 5.validateAtLeast(10) }
        assertEquals("Should be at least '10' but was '5'", exception.message)
        assertFailsWith<IllegalArgumentException> { 5.validateGreaterThan(5) }
        assertFailsWith<IllegalArgumentException> { 5.validateAtMost(4) }
        assertFailsWith<IllegalArgumentException> { 5.validateLessThan(5) }
        assertFailsWith<IllegalArgumentException> { 5.validateBetween(6, 7) }
        assertFailsWith<IllegalArgumentException> { 1.validateZero() }
        assertFailsWith<IllegalArgumentException> { 0.0.validateNotZero() }
    }

    @Test
    fun `custom messages are evaluated lazily`() {
        var evaluated = false
        5.validateAtLeast(1) { evaluated = true; "unused" }
        assertEquals(false, evaluated)
        val exception = assertFailsWith<IllegalArgumentException> { 5.validate({ it > 10 }) { "custom" } }
        assertEquals("custom", exception.message)
    }
}
