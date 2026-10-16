package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.ksp.di.Injection
import com.github.marcoral.versatia.ksp.di.injectedConstructor
import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.SubclassElement
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Applies the subclass enhancements found on the main class's lineage: every concrete class or
 * object in the sources assignable to the enhanced type becomes an element, the main class itself
 * as its `PluginTarget`. Classes are described like beans, with an injectable constructor;
 * abstract and sealed classes and interfaces are skipped. See docs/enhancements.md.
 */
internal class SubclassCollector(
    private val reporter: Reporter,
    private val mainClass: KSClassDeclaration,
    private val attributes: Attributes,
    private val injection: Injection,
) {
    fun collect(sources: Sources, rules: List<EnhancedAnnotation>): List<SubclassElement> {
        val active = rules.filter { it.subclassEnhancements.isNotEmpty() }
        if (active.isEmpty()) return emptyList()
        val mainName = mainClass.qualifiedName?.asString()
        return sources.classes
            .filter { it.classKind != ClassKind.ANNOTATION_CLASS && it.classKind != ClassKind.ENUM_ENTRY }
            .sortedBy { it.qualifiedName?.asString() }
            .flatMap { clazz ->
                val types = clazz.assignableTypes().map { it.canonicalName }.toSet()
                val matching = active.flatMap { rule -> rule.subclassEnhancements.filter { it.subclassOf.canonicalName in types }.map { rule to it } }
                if (matching.isEmpty()) return@flatMap emptyList()
                val described = if (clazz.qualifiedName?.asString() == mainName) null else describe(clazz, matching.first().first) ?: return@flatMap emptyList()
                matching.map { (rule, enhancement) ->
                    SubclassElement(rule.origin, rule.annotation, Enhancement(enhancement.processorClass, enhancement.phase), described)
                }
            }
    }

    private fun describe(clazz: KSClassDeclaration, rule: EnhancedAnnotation): ClassDeclaration? {
        val name = clazz.displayName()
        val carrier = "@${rule.annotation.type.simpleName} on ${rule.origin.simpleName}"
        return when {
            clazz.classKind == ClassKind.INTERFACE || clazz.isAbstract() -> null
            clazz.isCompanionObject ->
                reporter.fail(clazz, "$name is enhanced by $carrier but is a companion object, which the runtime does not reach; make it a named object")
            clazz.classKind == ClassKind.OBJECT ->
                ClassDeclaration(clazz.toClassName(), attributes.of(clazz, clazz), emptyList(), clazz.assignableTypes(), clazz.isReachable(), isObject = true, node = clazz)
            clazz.classKind == ClassKind.ENUM_CLASS ->
                reporter.fail(clazz, "$name is enhanced by $carrier but is an enum class, whose constants the runtime cannot create; use a class or an object")
            clazz.classKind != ClassKind.CLASS || Modifier.INNER in clazz.modifiers ->
                reporter.fail(clazz, "$name is enhanced by $carrier but is not a concrete, non-inner class or an object")
            clazz.typeParameters.isNotEmpty() -> reporter.fail(clazz, "$name is enhanced by $carrier and must not have type parameters")
            else -> {
                val constructor = clazz.injectedConstructor()
                    ?: return reporter.fail(clazz, "$name is enhanced by $carrier and must have a primary constructor or exactly one constructor")
                injection.instantiable(clazz, constructor)
            }
        }
    }
}
