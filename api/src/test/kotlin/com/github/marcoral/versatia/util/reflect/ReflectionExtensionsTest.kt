package com.github.marcoral.versatia.util.reflect

import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectionExtensionsTest {
    private object Holder {
        @Suppress("unused")
        private var counter = 1

        @Suppress("unused")
        private fun secret(prefix: String) = "$prefix$counter"
    }

    private class Plain {
        @Suppress("unused")
        private val hidden = "value"
    }

    @Test
    fun `isClassInClasspath uses the given loader and does not require initialization`() {
        val loader = javaClass.classLoader
        assertTrue(isClassInClasspath("com.github.marcoral.versatia.util.reflect.ReflectionExtensionsTest", loader))
        assertFalse(isClassInClasspath("com.github.marcoral.versatia.DoesNotExist", loader))
    }

    @Test
    fun `private properties of objects are read and written through static fields`() {
        val property = Holder::class.declaredMemberProperties.first { it.name == "counter" }
        @Suppress("UNCHECKED_CAST")
        val mutable = property as kotlin.reflect.KMutableProperty1<Holder, Int>
        assertEquals(1, mutable.forceGetValue(Holder))
        mutable.forceSetValue(Holder, 42)
        assertEquals(42, mutable.forceGetValue(Holder))
        assertFalse(mutable.isAccessible)
    }

    @Test
    fun `private properties of plain classes are read`() {
        val property = Plain::class.declaredMemberProperties.first { it.name == "hidden" }
        assertEquals("value", property.forceGetValue(Plain()))
    }

    @Test
    fun `private functions are invoked and accessibility is restored`() {
        val function = Holder::class.functions.first { it.name == "secret" }
        val result = function.forceInvoke(Holder, "n=")
        assertTrue(result.toString().startsWith("n="))
        assertFalse(function.isAccessible)
    }

    @Test
    fun `object instances are found even for private nested objects`() {
        assertEquals(Holder, Holder::class.getObjectInstanceOrThrow())
        assertNull(Plain::class.forceGetObjectInstance())
        assertFailsWith<IllegalStateException> { Plain::class.getObjectInstanceOrThrow() }
    }
}
