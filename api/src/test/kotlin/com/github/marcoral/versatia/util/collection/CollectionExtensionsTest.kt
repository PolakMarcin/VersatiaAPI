package com.github.marcoral.versatia.util.collection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectionExtensionsTest {
    @Test
    fun `addOrThrow rejects existing elements`() {
        val set = mutableSetOf(1)
        set.addOrThrow(2)
        assertFailsWith<IllegalStateException> { set.addOrThrow(1) }
        assertEquals(setOf(1, 2), set)
    }

    @Test
    fun `addAllOrThrow reports duplicates against the set and within the input`() {
        val set = mutableSetOf(1)
        val exception = assertFailsWith<IllegalStateException> { set.addAllOrThrow(listOf(1, 2, 2, 3)) }
        assertEquals("Duplicate elements: [1, 2]", exception.message)
        assertEquals(setOf(1), set)
    }

    @Test
    fun `addAllOrThrow accepts arrays and sequences`() {
        val set = mutableSetOf<Int>()
        set.addAllOrThrow(arrayOf(1, 2))
        set.addAllOrThrow(sequenceOf(3).constrainOnce())
        assertEquals(setOf(1, 2, 3), set)
    }
}
