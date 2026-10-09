package com.github.marcoral.versatia.util.collection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeepMergeMapTest {
    private fun assertMapEquals(expected: Map<String, Any?>, actual: Map<String, Any?>) = assertEquals(expected, actual)

    @Test
    fun `nested maps are merged key by key and collections are concatenated`() {
        val map = DeepMergeMap(mapOf("nested" to mapOf("a" to 1), "list" to listOf(1)))
        map += mapOf("nested" to mapOf("b" to 2), "list" to listOf(2), "new" to "x")
        assertMapEquals(mapOf("nested" to mapOf("a" to 1, "b" to 2), "list" to listOf(1, 2), "new" to "x"), map)
    }

    @Test
    fun `OVERRIDE replaces scalars`() {
        val map = DeepMergeMap(mapOf("a" to 1, "m" to mapOf("x" to 1)))
        map += mapOf("a" to 2, "m" to "scalar")
        assertMapEquals(mapOf("a" to 2, "m" to "scalar"), map)
    }

    @Test
    fun `KEEP_EXISTING keeps the first value`() {
        val map = DeepMergeMap(mapOf("a" to 1), MergeConflictStrategy.KEEP_EXISTING)
        map += mapOf("a" to 2)
        assertMapEquals(mapOf("a" to 1), map)
    }

    @Test
    fun `COMBINE_INTO_LIST is symmetric`() {
        val map = DeepMergeMap(mapOf("s" to 1, "l" to listOf(1), "s2" to 1), MergeConflictStrategy.COMBINE_INTO_LIST)
        map += mapOf("s" to 2, "l" to 2, "s2" to listOf(2, 3))
        assertMapEquals(mapOf("s" to listOf(1, 2), "l" to listOf(1, 2), "s2" to listOf(1, 2, 3)), map)
    }

    @Test
    fun `THROW names the key path`() {
        val map = DeepMergeMap(mapOf("outer" to mapOf("inner" to 1)), MergeConflictStrategy.THROW)
        val exception = assertFailsWith<IllegalStateException> { map += mapOf("outer" to mapOf("inner" to 2)) }
        assertEquals("Conflicting values at \"outer.inner\": existing \"1\", new \"2\"", exception.message)
    }

    @Test
    fun `null is an ordinary value`() {
        val map = DeepMergeMap(mapOf("a" to 1))
        map += mapOf("a" to null, "b" to null)
        assertMapEquals(mapOf("a" to null, "b" to null), map)
    }

    @Test
    fun `inputs are never mutated and mergedWith leaves the receiver alone`() {
        val original = mapOf("nested" to mapOf("a" to 1))
        val incoming = mapOf("nested" to mapOf("b" to 2))
        val base = DeepMergeMap(original)
        val merged = base.mergedWith(incoming)

        assertMapEquals(mapOf("nested" to mapOf("a" to 1, "b" to 2)), merged)
        assertMapEquals(mapOf("nested" to mapOf("a" to 1)), base)
        assertMapEquals(mapOf("nested" to mapOf("a" to 1)), original)
        assertMapEquals(mapOf("nested" to mapOf("b" to 2)), incoming)
    }

    @Test
    fun `equals hashCode and toString follow the map contract`() {
        val map = DeepMergeMap(mapOf("a" to 1))
        assertMapEquals(mapOf("a" to 1), map)
        assertEquals(mapOf("a" to 1).hashCode(), map.hashCode())
        assertEquals("{a=1}", map.toString())
    }
}
