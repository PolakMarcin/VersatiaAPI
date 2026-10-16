package com.github.marcoral.versatia.util.reflect

import java.lang.reflect.AccessibleObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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

/**
 * Makes this member accessible and returns it. The flag is shared by every user of the cached
 * member and never restored: restoring it would race with another thread using the same member.
 */
fun <T : AccessibleObject> T.forceAccessible() = apply { trySetAccessible() }

/** Sets the property through its setter, private or not, so a custom setter runs; a property without one is set through its field. */
fun <T, V> KMutableProperty1<T, V>.forceSetValue(instance: T, value: V) {
    // Static backing fields (properties of Kotlin objects) are set through java.lang.reflect alone,
    // without touching the kotlin-reflect accessors; see docs/api.md for the limitations this works around
    val field = javaField
    if (field == null || !Modifier.isStatic(field.modifiers)) {
        isAccessible = true
        return set(instance, value)
    }
    val setter = instance?.javaClass?.let { setterOf(it, name) }?.forceAccessible()
    if (setter == null) unboxingIfRejected(value) { field.forceAccessible().set(null, it) }
    else unboxingIfRejected(value) { unwrapping { setter.invoke(instance, it) } }
}

/** Reads the property through its getter, or through its field for a property of a Kotlin object. */
@Suppress("UNCHECKED_CAST")
fun <T, V> KProperty1<T, V>.forceGetValue(instance: T): V {
    val field = javaField
    if (field == null || !Modifier.isStatic(field.modifiers)) {
        isAccessible = true
        return get(instance)
    }
    return boxedAs(returnType.classifier as? KClass<*>, field.forceAccessible().get(null)) as V
}

fun KFunction<*>.forceInvoke(vararg args: Any?) = apply { isAccessible = true }.call(*args)

fun <T : Any> KClass<T>.getObjectInstanceOrThrow() =
    forceGetObjectInstance() ?: throw IllegalStateException("$qualifiedName is not declared as a Kotlin object")

/** Like [KClass.objectInstance], but also works for private nested objects. */
fun <T : Any> KClass<T>.forceGetObjectInstance() = try {
    objectInstance
} catch (e: IllegalAccessException) {
    java.objectInstanceOrNull()?.let { java.cast(it) }
}

/** The instance of this Kotlin `object`, read from its static `INSTANCE` field even when the object is private; null for any other class. */
fun Class<*>.objectInstanceOrNull(): Any? = declaredFields
    .firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) && it.type == this }
    ?.forceAccessible()
    ?.get(null)

/**
 * [objectInstanceOrNull], or else a new instance from the no-argument constructor, private or not.
 * An exception thrown by the constructor is rethrown as it is, not wrapped.
 */
fun Class<*>.objectOrNewInstance(): Any = objectInstanceOrNull() ?: unwrapping { getDeclaredConstructor().forceAccessible().newInstance() }

/**
 * The JVM setter of a property, found by name: kotlin-reflect cannot describe the accessors of an
 * inline class property of an object. The name of a setter taking an inline class carries a hash suffix.
 */
private fun setterOf(owner: Class<*>, propertyName: String): Method? {
    val isPrefixed = propertyName.length > 2 && propertyName.startsWith("is") && !propertyName[2].isLowerCase()
    val name = "set" + if (isPrefixed) propertyName.drop(2) else propertyName.replaceFirstChar { it.uppercaseChar() }
    return owner.declaredMethods.firstOrNull { (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == 1 && !Modifier.isStatic(it.modifiers) }
}

private inline fun <R> unwrapping(block: () -> R) = try {
    block()
} catch (e: InvocationTargetException) {
    throw e.targetException
}

/** A boxed inline class value, such as a `Percent`, is unboxed when the member takes the underlying type. */
private inline fun unboxingIfRejected(value: Any?, store: (Any?) -> Unit) = try {
    store(value)
} catch (e: IllegalArgumentException) {
    val unbox = value?.javaClass?.methods?.firstOrNull { it.name == "unbox-impl" && it.parameterCount == 0 } ?: throw e
    store(unbox.invoke(value))
}

/** The reverse of [unboxingIfRejected]: an underlying value read for an inline class type is boxed into it. */
private fun boxedAs(type: KClass<*>?, raw: Any?): Any? {
    if (raw == null || type == null || !type.isValue || type.isInstance(raw)) return raw
    val box = type.java.declaredMethods.firstOrNull { it.name == "box-impl" && Modifier.isStatic(it.modifiers) } ?: return raw
    return box.forceAccessible().invoke(null, raw)
}
