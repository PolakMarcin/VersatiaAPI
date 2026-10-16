package com.github.marcoral.versatia.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmSignaturesTest {
    @Test
    fun `reads parameter types as Class getName reports them`() {
        assertEquals(
            listOf("com.example.Clock", "int", "[Ljava.lang.String;", "[[J", "com.example.Outer\$Inner", "boolean"),
            JvmSignatures.parameterTypes("(Lcom/example/Clock;I[Ljava/lang/String;[[JLcom/example/Outer\$Inner;Z)V"),
        )
        assertEquals(emptyList(), JvmSignatures.parameterTypes("()V"))
    }

    @Test
    fun `gives up on a malformed descriptor`() {
        assertNull(JvmSignatures.parameterTypes("(Lcom/example/Clock)V"))
        assertNull(JvmSignatures.parameterTypes("(Q)V"))
        assertNull(JvmSignatures.parameterTypes("([)V"))
    }
}
