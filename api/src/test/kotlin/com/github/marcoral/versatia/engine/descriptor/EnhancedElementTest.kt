package com.github.marcoral.versatia.engine.descriptor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnhancedElementTest {
    annotation class Nested

    @Test
    fun `occurrenceOf finds a nested annotation by its recorded qualified name`() {
        val recorded = AnnotationOccurrence("com.github.marcoral.versatia.engine.descriptor.EnhancedElementTest.Nested")
        assertEquals(recorded, listOf(recorded).occurrenceOf<Nested>())
        assertNull(listOf(recorded).occurrenceOf<Deprecated>())
    }
}
