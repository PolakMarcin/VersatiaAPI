package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.VersatiaAttribute
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Records annotation occurrences for the descriptor: every annotation inheriting
 * `@VersatiaAttribute` on an element, and the enhancement annotation that produced an element.
 * An annotation class meta-annotated with an attribute, at any depth, counts as that attribute:
 * the inherited occurrences are recorded next to it, so whoever reads the attributes finds them
 * as if written on the element. An attribute reaching an element more than once is an error,
 * unless it is repeatable. Arguments are turned into descriptor values; see docs/enhancements.md.
 */
internal class Attributes(private val enhancements: EnhancementResolver, private val reporter: Reporter) {
    private val attributeName = VersatiaAttribute::class.qualifiedName!!
    private val repeatableNames = setOf(Repeatable::class.qualifiedName!!, java.lang.annotation.Repeatable::class.java.name)

    /** An annotation reaching an element: written on it ([carrier] null) or inherited from the annotation class [carrier]. */
    class Reached(val annotation: KSAnnotation, val declaration: KSClassDeclaration, val carrier: KSClassDeclaration?) {
        val name get() = declaration.qualifiedName?.asString()
    }

    /** The attributes written on [annotated]; errors point at [usage]. */
    fun of(annotated: KSAnnotated, usage: KSNode) = reaching(annotated).map { occurrence(it.annotation, usage) }

    /**
     * Every annotation on [annotated] inheriting `@VersatiaAttribute`, each followed by the
     * attributes it inherits; an attribute reaching [annotated] twice is reported there.
     */
    fun reaching(annotated: KSAnnotated): List<Reached> {
        val result = mutableListOf<Reached>()
        val expanded = mutableSetOf<String>()
        fun expand(declaration: KSClassDeclaration) {
            if (!expanded.add(declaration.qualifiedName?.asString() ?: return)) return
            for (meta in declaration.annotations.flatMap(::unwrapped)) {
                val metaDeclaration = meta.declaration()?.takeIf { inheritsAttribute(meta) } ?: continue
                if (metaDeclaration.isAttribute()) result += Reached(meta, metaDeclaration, declaration)
                expand(metaDeclaration)
            }
        }
        for (annotation in annotated.annotations.flatMap(::unwrapped)) {
            val declaration = annotation.declaration()?.takeIf { inheritsAttribute(annotation) } ?: continue
            result += Reached(annotation, declaration, null)
            expand(declaration)
        }
        reportRepeated(annotated, result)
        return result
    }

    private fun reportRepeated(annotated: KSAnnotated, reached: List<Reached>) = reached
        .filter { it.declaration.isAttribute() && !it.declaration.isRepeatable() }
        .groupBy { it.name }.values.filter { it.size > 1 }
        .forEach { group ->
            val sources = group.joinToString(" and ") { found -> found.carrier?.let { "inherited from @${it.simpleName.asString()}" } ?: "written directly" }
            reporter.fail(annotated, "${annotated.label()} has @${group.first().declaration.simpleName.asString()} more than once: $sources; keep one")
        }

    private fun inheritsAttribute(annotation: KSAnnotation) = enhancements.inherits(enhancements.chainOf(annotation), attributeName)

    private fun KSClassDeclaration.isAttribute() = annotations.any { it.declaration()?.qualifiedName?.asString() == attributeName }

    private fun KSClassDeclaration.isRepeatable() = annotations.any { it.declaration()?.qualifiedName?.asString() in repeatableNames }

    /** Several occurrences of a repeatable annotation compiled into a class arrive as one `Container` annotation. */
    private fun unwrapped(annotation: KSAnnotation): List<KSAnnotation> {
        val declaration = annotation.declaration()
        val repeated = declaration?.simpleName?.asString() == "Container" && (declaration.parentDeclaration as? KSClassDeclaration)?.isRepeatable() == true
        return if (repeated) (annotation.argument("value") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>() else listOf(annotation)
    }

    private fun KSAnnotated.label() = when (this) {
        is KSValueParameter -> "Parameter ${name?.asString()}"
        is KSFunctionDeclaration -> "${simpleName.asString()}()"
        is KSDeclaration -> simpleName.asString()
        else -> "The element"
    }

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

    /** Enum entries become their constant's name, classes their qualified name. */
    private fun descriptorValue(value: Any?, usage: KSNode, what: String): Any? = when (value) {
        null, is String, is Boolean, is Number, is Char -> value
        is KSClassDeclaration -> enumEntryName(value) ?: value.qualifiedName?.asString()
        is KSType -> enumEntryName(value) ?: value.expanded().declaration.qualifiedName?.asString()
        is Enum<*> -> value.name
        is List<*> -> value.map { descriptorValue(it, usage, what) }
        is Array<*> -> value.map { descriptorValue(it, usage, what) }
        else -> reporter.fail(usage, "Cannot record $what in the descriptor: values of type ${value.javaClass.simpleName} are not supported")
    }
}
