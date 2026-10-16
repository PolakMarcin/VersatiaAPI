package com.github.marcoral.versatia.util.reflect

import com.github.marcoral.versatia.util.type.Percent
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.functions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReflectionExtensionsTest {
    private object Holder {
        @Suppress("unused")
        private var counter = 1

        @Suppress("unused")
        private fun secret(prefix: String) = "$prefix$counter"
    }

    private object Timer {
        var restarts = 0

        @Suppress("unused")
        var interval = 20L
            private set(value) {
                field = value
                restarts++
            }

        @Suppress("unused")
        private var chance = Percent.ZERO
    }

    private class Plain {
        @Suppress("unused")
        private val hidden = "value"
    }

    private class Failing {
        init {
            throw IllegalStateException("constructor failed")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any, V> mutable(name: String) = T::class.declaredMemberProperties.first { it.name == name } as KMutableProperty1<T, V>

    @Test
    fun `isClassInClasspath uses the given loader and does not require initialization`() {
        val loader = javaClass.classLoader
        assertTrue(isClassInClasspath("com.github.marcoral.versatia.util.reflect.ReflectionExtensionsTest", loader))
        assertFalse(isClassInClasspath("com.github.marcoral.versatia.DoesNotExist", loader))
    }

    @Test
    fun `private properties of objects are read and written through static fields`() {
        val property = mutable<Holder, Int>("counter")
        assertEquals(1, property.forceGetValue(Holder))
        property.forceSetValue(Holder, 42)
        assertEquals(42, property.forceGetValue(Holder))
    }

    @Test
    fun `a private custom setter of an object runs`() {
        val restarts = Timer.restarts
        mutable<Timer, Long>("interval").forceSetValue(Timer, 40L)
        assertEquals(40L, Timer.interval)
        assertEquals(restarts + 1, Timer.restarts)
    }

    @Test
    fun `inline class values of objects are unboxed when written and boxed when read`() {
        val property = mutable<Timer, Percent>("chance")
        property.forceSetValue(Timer, Percent.of(12.5))
        val read: Percent = property.forceGetValue(Timer)
        assertEquals(Percent.of(12.5), read)
    }

    @Test
    fun `private properties of plain classes are read`() {
        val property = Plain::class.declaredMemberProperties.first { it.name == "hidden" }
        assertEquals("value", property.forceGetValue(Plain()))
    }

    @Test
    fun `private functions are invoked`() {
        val function = Holder::class.functions.first { it.name == "secret" }
        val result = function.forceInvoke(Holder, "n=")
        assertTrue(result.toString().startsWith("n="))
    }

    @Test
    fun `object instances are found even for private nested objects`() {
        assertEquals(Holder, Holder::class.getObjectInstanceOrThrow())
        assertNull(Plain::class.forceGetObjectInstance())
        assertFailsWith<IllegalStateException> { Plain::class.getObjectInstanceOrThrow() }
        assertSame(Holder, Holder::class.java.objectInstanceOrNull())
        assertNull(Plain::class.java.objectInstanceOrNull())
    }

    @Test
    fun `objectOrNewInstance returns the object or a new instance and unwraps constructor failures`() {
        assertSame(Holder, Holder::class.java.objectOrNewInstance())
        assertTrue(Plain::class.java.objectOrNewInstance() is Plain)
        assertEquals("constructor failed", assertFailsWith<IllegalStateException> { Failing::class.java.objectOrNewInstance() }.message)
    }
}
