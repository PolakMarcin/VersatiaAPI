package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.VersatiaInternalApi
import com.github.marcoral.versatia.util.reflect.forceAccessible
import java.lang.reflect.InvocationTargetException

sealed interface Invoker {
    fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>)

    /** Generated code calls the function itself; used whenever it is visible to that code. */
    @VersatiaInternalApi
    class Direct(private val call: (Any, List<Any?>) -> Unit) : Invoker {
        override fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>) = call(instance, arguments)

        override fun toString() = "Direct"
    }

    /** Fallback for private functions or classes. Names are JVM binary names. */
    @VersatiaInternalApi
    data class Reflective(
        val binaryClassName: String,
        val methodName: String,
        val parameterBinaryClassNames: List<String>,
    ) : Invoker {
        private val methodFor = PerLoader { classLoader ->
            loadDescribedClass(binaryClassName, false, classLoader).declaredMethods
                .firstOrNull { it.name == methodName && it.parameterTypes.map { type -> type.name } == parameterBinaryClassNames }
                ?.forceAccessible()
                ?: throw EnhancementException("$binaryClassName has no method $methodName(${parameterBinaryClassNames.joinToString()})")
        }

        override fun invoke(classLoader: ClassLoader, instance: Any, arguments: List<Any?>) {
            try {
                methodFor(classLoader).invoke(instance, *arguments.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
