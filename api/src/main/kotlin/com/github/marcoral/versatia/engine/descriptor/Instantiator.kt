package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.util.reflect.forceAccessible
import com.github.marcoral.versatia.util.reflect.objectInstanceOrNull
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaConstructor

sealed interface Instantiator {
    fun create(classLoader: ClassLoader, arguments: List<Any?>): Any

    /** Generated code calls the constructor (or names the object) itself; used whenever the class is visible to it. */
    class Direct(private val factory: (List<Any?>) -> Any) : Instantiator {
        override fun create(classLoader: ClassLoader, arguments: List<Any?>) = factory(arguments)

        override fun toString() = "Direct"
    }

    /** Fallback for private classes. Names are JVM binary names (`com.example.Outer$Inner`). */
    data class Reflective(
        val binaryClassName: String,
        val parameterBinaryClassNames: List<String>,
    ) : Instantiator {
        override fun create(classLoader: ClassLoader, arguments: List<Any?>): Any {
            val clazz = loadDescribedClass(binaryClassName, false, classLoader)
            val constructor = clazz.declaredConstructors
                .firstOrNull { it.parameterTypes.map { type -> type.name } == parameterBinaryClassNames }
                ?.forceAccessible()
                ?: throw EnhancementException(
                    "No constructor of $binaryClassName takes ${parameterBinaryClassNames.joinToString()}",
                )
            val unboxed = arguments.zip(constructor.parameterTypes) { argument, type -> unboxedFor(type, argument) }
            return try {
                constructor.newInstance(*unboxed.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /**
     * For constructors with optional parameters, reachable or not: called through Kotlin
     * reflection, so that an argument given as [DefaultArgument] keeps the parameter's default.
     */
    data class WithDefaults(
        val binaryClassName: String,
        val parameterBinaryClassNames: List<String>,
    ) : Instantiator {
        override fun create(classLoader: ClassLoader, arguments: List<Any?>): Any {
            val clazz = loadDescribedClass(binaryClassName, false, classLoader)
            // A constructor taking a value class is reached through a synthetic one that adds a marker parameter
            val marked = parameterBinaryClassNames + "kotlin.jvm.internal.DefaultConstructorMarker"
            val constructor = clazz.kotlin.constructors
                .firstOrNull { it.javaConstructor?.parameterTypes?.map { type -> type.name }.let { names -> names == parameterBinaryClassNames || names == marked } }
                ?.apply { isAccessible = true }
                ?: throw EnhancementException("No constructor of $binaryClassName takes ${parameterBinaryClassNames.joinToString()}")
            val given = constructor.parameters.zip(arguments).filter { (_, argument) -> argument !== DefaultArgument }.toMap()
            return try {
                constructor.callBy(given)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /** Fallback for private Kotlin `object`s: the single instance, read from its static field. */
    data class ObjectInstance(val binaryClassName: String) : Instantiator {
        override fun create(classLoader: ClassLoader, arguments: List<Any?>) =
            loadDescribedClass(binaryClassName, true, classLoader).objectInstanceOrNull() ?: throw EnhancementException("$binaryClassName is not a Kotlin object")
    }
}

/**
 * Stands in the arguments of [Instantiator.create] for an optional constructor parameter that no
 * bean provides, so that it keeps its default value; only [Instantiator.WithDefaults] accepts it.
 */
data object DefaultArgument

/**
 * [argument] as a JVM parameter of [type] takes it: a Kotlin value class, such as a `Percent`, is
 * compiled to its underlying type wherever it is not nullable, so a boxed instance is unboxed.
 */
private tailrec fun unboxedFor(type: Class<*>, argument: Any?): Any? {
    if (argument == null || !type.isPrimitive && type.isInstance(argument)) return argument
    val unbox = argument.javaClass.declaredMethods.firstOrNull { it.name == "unbox-impl" && it.parameterCount == 0 } ?: return argument
    return unboxedFor(type, unbox.forceAccessible().invoke(argument))
}

/** A class named by a descriptor; a class the plugin no longer has is reported as an [EnhancementException]. */
internal fun loadDescribedClass(binaryClassName: String, initialize: Boolean, classLoader: ClassLoader): Class<*> = try {
    Class.forName(binaryClassName, initialize, classLoader)
} catch (e: ClassNotFoundException) {
    throw EnhancementException("$binaryClassName named by the descriptor cannot be loaded; rebuild the plugin", e)
}
