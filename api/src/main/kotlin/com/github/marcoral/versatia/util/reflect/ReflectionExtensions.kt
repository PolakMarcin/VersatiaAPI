package com.github.marcoral.versatia.util.reflect

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
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

fun <T, V> KMutableProperty1<T, V>.forceSetValue(instance: T, value: V) {
    // Static backing fields (properties of Kotlin objects) are set through java.lang.reflect alone,
    // without touching the kotlin-reflect accessors; see docs/api.md for the limitations this works around
    val field = javaField
    if (field != null && Modifier.isStatic(field.modifiers)) field.withAccessible { field.setStatic(value) } else withAccessible { set(instance, value) }
}

/** A boxed inline class value, such as a `Percent`, is unboxed when the field holds the underlying type. */
private fun Field.setStatic(value: Any?) = try {
    set(null, value)
} catch (e: IllegalArgumentException) {
    val unbox = value?.javaClass?.methods?.firstOrNull { it.name == "unbox-impl" && it.parameterCount == 0 } ?: throw e
    set(null, unbox.invoke(value))
}

@Suppress("UNCHECKED_CAST")
fun <T, V> KProperty1<T, V>.forceGetValue(instance: T): V {
    val field = javaField
    return if (field != null && Modifier.isStatic(field.modifiers)) field.withAccessible { field.get(null) as V } else withAccessible { get(instance) }
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

/** For static members only: [AccessibleObject.canAccess] takes no instance for them. */
private inline fun <R> AccessibleObject.withAccessible(block: () -> R): R {
    val wasAccessible = canAccess(null)
    isAccessible = true
    try {
        return block()
    } finally {
        isAccessible = wasAccessible
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
