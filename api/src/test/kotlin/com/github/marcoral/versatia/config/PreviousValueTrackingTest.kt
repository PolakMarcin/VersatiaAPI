package com.github.marcoral.versatia.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviousValueTrackingTest {
    @Test
    fun `remembers the value replaced by the last assignment`() {
        val tracked = PreviousValueTracking<Int>()
        assertFailsWith<IllegalStateException> { tracked.value }
        assertFalse(tracked.changed)

        tracked.value = 1
        assertEquals(1, tracked.value)
        assertNull(tracked.previous)
        assertFalse(tracked.hasPrevious)
        assertTrue(tracked.changed, "the first assignment counts as a change")

        tracked.value = 1
        assertEquals(1, tracked.previous)
        assertTrue(tracked.hasPrevious)
        assertFalse(tracked.changed)

        tracked.value = 2
        assertEquals(1, tracked.previous)
        assertTrue(tracked.changed)
    }

    @Test
    fun `tells a null value from no value`() {
        val tracked = PreviousValueTracking<String?>()
        tracked.value = null
        assertNull(tracked.value)
        assertTrue(tracked.changed)

        tracked.value = "x"
        assertNull(tracked.previous)
        assertTrue(tracked.hasPrevious)
        assertTrue(tracked.changed)
        assertEquals("PreviousValueTracking(x, previous=null)", tracked.toString())
    }
}
