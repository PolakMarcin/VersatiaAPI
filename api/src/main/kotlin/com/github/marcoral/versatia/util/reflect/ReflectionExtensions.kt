package com.github.marcoral.versatia.util.reflect

import java.lang.reflect.Modifier
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * Whether [classLoader] can see a class named [qualifiedClassName]. The class is not initialized.
 * On a server every plugin has its own class loader, so pass the loader of the plugin that asks.
 */
fun isClassInClasspath(qualifiedClassName: String, classLoader: ClassLoader) = try {
    Class.forName(qualifiedClassName, false, classLoader)
    true
} catch (e: ClassNotFoundException) {
    false
} catch (e: LinkageError) {
    false
}

fun <T, V> KMutableProperty1<T, V>.forceSetValue(instance: T, value: V) = withAccessible {
    val field = javaField
    // Static backing fields (properties of Kotlin objects) must be set through java.lang.reflect;
    // see docs/api.md for the kotlin-reflect limitation this works around
    if (field != null && Modifier.isStatic(field.modifiers)) field.set(null, value) else set(instance, value)
}

@Suppress("UNCHECKED_CAST")
fun <T, V> KProperty1<T, V>.forceGetValue(instance: T) = withAccessible {
    val field = javaField
    if (field != null && Modifier.isStatic(field.modifiers)) field.get(null) as V else get(instance)
}

fun KFunction<*>.forceInvoke(vararg args: Any?) = withAccessible { call(*args) }

fun <T : Any> KClass<T>.getObjectInstanceOrThrow() =
    forceGetObjectInstance() ?: throw IllegalStateException("$qualifiedName is not declared as a Kotlin object")

/** Like [KClass.objectInstance], but also works for private nested objects. */
fun <T : Any> KClass<T>.forceGetObjectInstance() = try {
    objectInstance
} catch (e: IllegalAccessException) {
    readInstanceField()
}

private fun <T : Any> KClass<T>.readInstanceField(): T? {
    val field = runCatching { java.getDeclaredField("INSTANCE") }.getOrNull() ?: return null
    if (!Modifier.isStatic(field.modifiers) || !java.isAssignableFrom(field.type)) return null
    val wasAccessible = field.canAccess(null)
    field.isAccessible = true
    try {
        return java.cast(field.get(null))
    } finally {
        field.isAccessible = wasAccessible
    }
}

private inline fun <R> KCallable<*>.withAccessible(block: () -> R): R {
    val wasAccessible = isAccessible
    isAccessible = true
    try {
        return block()
    } finally {
        isAccessible = wasAccessible
    }
}
