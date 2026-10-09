package com.github.marcoral.versatia.util.type

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PercentTest {
    @Test
    fun `of accepts the closed range 0 to 100`() {
        assertEquals(0.0, Percent.of(0.0).value)
        assertEquals(100.0, Percent.of(100.0).value)
        assertFailsWith<IllegalArgumentException> { Percent.of(-0.1) }
        assertFailsWith<IllegalArgumentException> { Percent.of(100.1) }
    }

    @Test
    fun `ofNormalizedValue takes 0 to 1`() {
        assertEquals(50.0, Percent.ofNormalizedValue(0.5).value)
        assertEquals(0.5, Percent.ofNormalizedValue(0.5).normalizedValue)
        assertFailsWith<IllegalArgumentException> { Percent.ofNormalizedValue(50.0) }
    }

    @Test
    fun `ratio constructors differ in out-of-range handling`() {
        assertEquals(25.0, Percent.of(1, 4).value)
        assertFailsWith<IllegalArgumentException> { Percent.of(5, 4) }
        assertEquals(100.0, Percent.ofCoerced(5, 4).value)
        assertEquals(0.0, Percent.ofCoerced(-1, 4).value)
        assertFailsWith<IllegalArgumentException> { Percent.of(1, 0) }
    }

    @Test
    fun `parse accepts percent strings only`() {
        assertEquals(12.5, Percent.parse("12.5%").value)
        assertEquals(7.0, Percent.parse(" 7 % ").value)
        assertFailsWith<IllegalArgumentException> { Percent.parse("12.5") }
        assertFailsWith<IllegalArgumentException> { Percent.parse("abc%") }
        assertFailsWith<IllegalArgumentException> { Percent.parse("150%") }
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertEquals(Percent.of(12.5), Percent.parseOrNull("12.5%"))
        assertNull(Percent.parseOrNull("12.5"))
        assertNull(Percent.parseOrNull("150%"))
    }

    @Test
    fun `percents are comparable and printable`() {
        assertTrue(Percent.of(10.0) < Percent.of(20.0))
        assertEquals("12.5%", Percent.of(12.5).toString())
    }
}
