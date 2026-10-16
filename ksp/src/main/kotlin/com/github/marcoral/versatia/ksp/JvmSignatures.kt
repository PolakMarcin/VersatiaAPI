package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/**
 * The names the JVM knows a function by, which reflective invokers and instantiators look up:
 * `internal` functions, `@JvmName` and value-class parameters change the method name, and
 * Kotlin types such as `kotlin.String` or `kotlin.Int` map to `java.lang.String` and `int`.
 */
@OptIn(KspExperimental::class)
internal class JvmSignatures(private val resolver: Resolver) {
    fun nameOf(function: KSFunctionDeclaration) = resolver.getJvmName(function) ?: function.simpleName.asString()

    /** The parameter types as `Class.getName` reports them, or null when KSP cannot map the signature. */
    fun parameterTypesOf(function: KSFunctionDeclaration) = resolver.mapToJvmSignature(function)?.let(::parameterTypes)

    companion object {
        private val PRIMITIVES = mapOf(
            'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double",
        )

        /** The parameter types of a JVM method descriptor such as `(Lcom/example/Clock;I[Ljava/lang/String;)V`. */
        fun parameterTypes(descriptor: String): List<String>? {
            val parameters = descriptor.substringAfter('(', "").substringBefore(')', "")
            val result = mutableListOf<String>()
            var index = 0
            while (index < parameters.length) {
                val start = index
                while (parameters[index] == '[') if (++index == parameters.length) return null
                val end = if (parameters[index] == 'L') parameters.indexOf(';', index).takeIf { it >= 0 } ?: return null else index
                val type = parameters.substring(start, end + 1)
                result += when {
                    type.startsWith('[') -> type.replace('/', '.')
                    type.startsWith('L') -> type.substring(1, type.length - 1).replace('/', '.')
                    else -> PRIMITIVES[type.single()] ?: return null
                }
                index = end + 1
            }
            return result
        }
    }
}
