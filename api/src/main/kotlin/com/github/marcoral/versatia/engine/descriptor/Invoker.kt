package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.util.reflect.forceAccessible
import java.lang.reflect.InvocationTargetException

sealed interface Invoker {
    fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>)

    /** Generated code calls the function itself; used whenever it is visible to that code. */
    class Direct(private val call: (Any, List<Any?>) -> Unit) : Invoker {
        override fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>) = call(instance, arguments)

        override fun toString() = "Direct"
    }

    /** Fallback for private functions or classes. Names are JVM binary names. */
    data class Reflective(
        val binaryClassName: String,
        val methodName: String,
        val parameterBinaryClassNames: List<String>,
    ) : Invoker {
        override fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>) {
            val clazz = loadDescribedClass(binaryClassName, false, classLoader)
            val method = clazz.declaredMethods
                .firstOrNull { it.name == methodName && it.parameterTypes.map { type -> type.name } == parameterBinaryClassNames }
                ?.forceAccessible()
                ?: throw EnhancementException(
                    "$binaryClassName has no method $methodName(${parameterBinaryClassNames.joinToString()})",
                )
            try {
                method.invoke(instance, *arguments.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
