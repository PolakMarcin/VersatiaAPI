package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.ksp.di.allReachable
import com.github.marcoral.versatia.ksp.di.injectableParametersOf
import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.SubclassElement
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Applies the subclass enhancements found on the main class's lineage: every concrete class or
 * object in the sources assignable to the enhanced type becomes an element, the main class itself
 * as its `PluginTarget`. Classes are described like beans, with an injectable constructor; see
 * docs/enhancements.md.
 */
internal class SubclassCollector(
    private val logger: KSPLogger,
    private val mainClass: KSClassDeclaration,
    private val attributes: Attributes,
) {
    private var failed = false

    val hadErrors get() = failed

    fun collect(files: List<KSFile>, rules: List<EnhancedAnnotation>): List<SubclassElement> {
        val active = rules.filter { it.subclassEnhancements.isNotEmpty() }
        if (active.isEmpty()) return emptyList()
        val mainName = mainClass.qualifiedName?.asString()
        return files.flatMap { it.declarations.flatMap(::classesIn) }
            .filter { it.classKind != ClassKind.ANNOTATION_CLASS && !it.isCompanionObject }
            .sortedBy { it.qualifiedName?.asString() }
            .flatMap { clazz ->
                val types = clazz.assignableTypes().map { it.canonicalName }.toSet()
                val matching = active.flatMap { rule -> rule.subclassEnhancements.filter { it.subclassOf.canonicalName in types }.map { rule to it } }
                if (matching.isEmpty()) return@flatMap emptyList()
                val described = if (clazz.qualifiedName?.asString() == mainName) null else describe(clazz, matching.first().first) ?: return@flatMap emptyList()
                matching.map { (rule, enhancement) ->
                    SubclassElement(rule.origin!!, rule.annotation, Enhancement(enhancement.processorClass, enhancement.phase), described)
                }
            }
    }

    private fun describe(clazz: KSClassDeclaration, rule: EnhancedAnnotation): ClassDeclaration? {
        val name = clazz.simpleName.asString()
        val carrier = "@${rule.annotation.type.simpleName} on ${rule.origin!!.simpleName}"
        if (clazz.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in clazz.modifiers) return null
        if (clazz.classKind == ClassKind.OBJECT) {
            return ClassDeclaration(clazz.toClassName(), attributes.of(clazz, clazz), emptyList(), clazz.assignableTypes(), clazz.isReachable(), isObject = true, node = clazz)
        }
        if (clazz.classKind != ClassKind.CLASS || Modifier.INNER in clazz.modifiers)
            return fail(clazz, "$name is enhanced by $carrier but is not a concrete, non-inner class or an object")
        if (clazz.typeParameters.isNotEmpty()) return fail(clazz, "$name is enhanced by $carrier and must not have type parameters")
        val constructor = clazz.primaryConstructor ?: clazz.getConstructors().singleOrNull()
            ?: return fail(clazz, "$name is enhanced by $carrier and must have exactly one constructor")
        val parameters = injectableParametersOf(constructor.parameters, attributes, ::fail) ?: return null
        return ClassDeclaration(
            type = clazz.toClassName(),
            annotations = attributes.of(clazz, clazz),
            parameters = parameters.map { it.recorded },
            assignableTypes = clazz.assignableTypes(),
            reachable = clazz.isReachable() && constructor.isReachable() && parameters.allReachable(),
            node = clazz,
        )
    }

    private fun fail(node: KSNode, message: String): Nothing? {
        failed = true
        logger.error(message, node)
        return null
    }
}
