package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import java.lang.reflect.InvocationTargetException

sealed interface Instantiator {
    fun create(classLoader: ClassLoader, arguments: List<Any?>): Any

    /** Generated code calls the constructor itself; used whenever the class is visible to it. */
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
            val clazz = Class.forName(binaryClassName, false, classLoader)
            val constructor = clazz.declaredConstructors
                .firstOrNull { it.parameterTypes.map { type -> type.name } == parameterBinaryClassNames }
                ?: throw EnhancementException(
                    "No constructor of $binaryClassName takes ${parameterBinaryClassNames.joinToString()}",
                )
            constructor.isAccessible = true
            return try {
                constructor.newInstance(*arguments.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
