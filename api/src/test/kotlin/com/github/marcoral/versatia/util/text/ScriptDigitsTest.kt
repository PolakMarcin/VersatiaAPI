package com.github.marcoral.versatia.util.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptDigitsTest {
    @Test
    fun `digits and a leading minus are converted`() {
        assertEquals("₁₂₃", 123.toSubscript())
        assertEquals("⁻⁴⁵", (-45L).toSuperscript())
        assertEquals("₀", 0u.toSubscript())
        assertEquals("⁹⁹", 99uL.toSuperscript())
        assertEquals("", "".toSubscript())
    }

    @Test
    fun `anything else is rejected`() {
        assertFailsWith<IllegalArgumentException> { "1.5".toSubscript() }
        assertFailsWith<IllegalArgumentException> { "1-2".toSuperscript() }
        assertFailsWith<IllegalArgumentException> { "x".toSubscript() }
    }
}
