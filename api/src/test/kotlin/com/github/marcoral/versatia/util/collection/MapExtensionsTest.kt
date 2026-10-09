package com.github.marcoral.versatia.util.collection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MapExtensionsTest {
    @Test
    fun `getOrThrow distinguishes a null value from a missing key`() {
        val map = mapOf<String, String?>("present" to null)
        assertNull(map.getOrThrow("present"))
        assertFailsWith<NoSuchElementException> { map.getOrThrow("missing") }
    }

    @Test
    fun `putOrThrow rejects keys that hold null`() {
        val map = mutableMapOf<String, String?>("a" to null)
        assertFailsWith<IllegalStateException> { map.putOrThrow("a", "x") }
        map.putOrThrow("b", "y")
        assertEquals(mapOf("a" to null, "b" to "y"), map)
    }

    @Test
    fun `putAllOrThrow reports conflicts with the map and within the input and leaves the map untouched`() {
        val map = mutableMapOf("a" to 1)
        val exception = assertFailsWith<IllegalStateException> {
            map.putAllOrThrow(listOf("a" to 2, "b" to 3, "b" to 4))
        }
        assertEquals(mapOf("a" to 1), map)
        assertEquals("Duplicate keys: [key: \"a\", existing: \"1\", new: \"2\"], [key: \"b\", existing: \"3\", new: \"4\"]", exception.message)
    }

    @Test
    fun `putAllOrThrow consumes a one-shot sequence only once`() {
        val map = mutableMapOf<String, Int>()
        map.putAllOrThrow(sequenceOf("a" to 1, "b" to 2).constrainOnce())
        assertEquals(mapOf("a" to 1, "b" to 2), map)
    }

    @Test
    fun `putAllOrThrow accepts maps and arrays`() {
        val map = mutableMapOf<String, Int>()
        map.putAllOrThrow(mapOf("a" to 1))
        map.putAllOrThrow(arrayOf("b" to 2))
        assertEquals(mapOf("a" to 1, "b" to 2), map)
    }

    @Test
    fun `mergeOrThrow treats null values as present`() {
        val merged = listOf(mapOf("a" to 1, "b" to null), mapOf("c" to 3)).mergeOrThrow()
        assertEquals(mapOf("a" to 1, "b" to null, "c" to 3), merged)
        val exception = assertFailsWith<IllegalStateException> {
            listOf(mapOf("a" to null), mapOf("a" to 2)).mergeOrThrow()
        }
        assertEquals("Duplicate keys: [key: \"a\", existing: \"null\", new: \"2\"]", exception.message)
    }
}
