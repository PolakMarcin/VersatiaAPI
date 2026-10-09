package com.github.marcoral.versatia.util.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class Mode { FAST, SLOW }

class EnumExtensionsTest {
    @Test
    fun `names are matched ignoring case`() {
        assertEquals(Mode.FAST, asEnumIgnoreCase<Mode>("fast"))
        assertEquals(Mode.SLOW, asEnumIgnoreCaseOrThrow<Mode>("Slow"))
        assertTrue(isEnumIgnoreCase<Mode>("FAST"))
    }

    @Test
    fun `unknown names are reported with the allowed values`() {
        assertNull(asEnumIgnoreCase<Mode>("medium"))
        assertFalse(isEnumIgnoreCase<Mode>("medium"))
        val exception = assertFailsWith<IllegalArgumentException> { asEnumIgnoreCaseOrThrow<Mode>("medium") }
        assertEquals("No enum constant of com.github.marcoral.versatia.util.lang.Mode matches \"medium\" (ignoring case); allowed: FAST, SLOW", exception.message)
    }
}
