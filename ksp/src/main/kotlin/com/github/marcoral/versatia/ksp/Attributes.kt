package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.VersatiaAttribute
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Records annotation occurrences for the descriptor: every annotation inheriting
 * `@VersatiaAttribute` on an element, and the enhancement annotation that produced an element.
 * Arguments are turned into descriptor values; see docs/enhancements.md.
 */
internal class Attributes(private val enhancements: EnhancementResolver, private val logger: KSPLogger) {
    var hadErrors = false
        private set

    private val attributeName = VersatiaAttribute::class.qualifiedName!!

    /** The attributes written on [annotated]; errors point at [usage]. */
    fun of(annotated: KSAnnotated, usage: KSNode) = annotated.annotations
        .filter { annotation -> enhancements.chainOf(annotation).any { it.declaration.qualifiedName?.asString() == attributeName } }
        .map { occurrence(it, usage) }
        .toList()

    /** [annotation] as the descriptor records it, with all its arguments. */
    fun occurrence(annotation: KSAnnotation, usage: KSNode): AnnotationRef {
        val declaration = annotation.declaration()!!
        val type = declaration.toClassName()
        val names = declaration.primaryConstructor?.parameters?.mapNotNull { it.name?.asString() }.orEmpty()
        val written = annotation.arguments.associate { it.name?.asString() to it.value }
        val defaults = annotation.defaultArguments.associate { it.name?.asString() to it.value }
        val arguments = names.associateWith { name ->
            val value = if (name in written) written[name] else defaults[name]
            descriptorValue(value, usage, "argument \"$name\" of @${type.simpleName}")
        }
        return AnnotationRef(type, arguments)
    }

    private fun descriptorValue(value: Any?, usage: KSNode, what: String): Any? = when (value) {
        null, is String, is Boolean, is Number, is Char -> value
        is KSClassDeclaration -> value.simpleName.asString()
        is KSType -> value.declaration.qualifiedName?.asString()
        is Enum<*> -> value.name
        is List<*> -> value.map { descriptorValue(it, usage, what) }
        is Array<*> -> value.map { descriptorValue(it, usage, what) }
        else -> {
            hadErrors = true
            logger.error("Cannot record $what in the descriptor: values of type ${value.javaClass.simpleName} are not supported", usage)
            null
        }
    }
}
