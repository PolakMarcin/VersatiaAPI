package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.di.BeanNames
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.assignableTypes
import com.github.marcoral.versatia.ksp.classesIn
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every class annotated with a scope that inherits `@Bean` and describes it. The plugin's
 * main class is described separately, with the enhancements the generic `PluginCollector` found.
 */
internal class BeanCollector(
    private val logger: KSPLogger,
    private val mainClass: KSClassDeclaration,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
) {
    private var failed = false

    val hadErrors get() = failed

    private val beanAnnotation = Bean::class.qualifiedName!!

    fun collect(files: List<KSFile>) = files
        .flatMap { file -> file.declarations.flatMap(::classesIn) }
        .mapNotNull(::describe)

    /** The main class: its bean is always named `BeanNames.PLUGIN`, so `@Qualifier` is rejected; `@Primary` applies. */
    fun describePlugin(enhancements: List<EnhancedAnnotation>): PluginDeclaration {
        val recorded = attributes.of(mainClass, mainClass)
        if (recorded.qualifier() != null)
            fail(mainClass, "${mainClass.simpleName.asString()} is the plugin's main class and its bean is always named \"${BeanNames.PLUGIN}\"; remove @Qualifier")
        return PluginDeclaration(
            type = mainClass.toClassName(),
            primary = recorded.isPrimary(),
            annotations = recorded,
            assignableTypes = mainClass.assignableTypes(),
            enhancements = enhancements,
            node = mainClass,
        )
    }

    private fun describe(clazz: KSClassDeclaration): BeanDeclaration? {
        // An annotation class carrying a scope defines a new scope, it is not a bean
        if (clazz.classKind == ClassKind.ANNOTATION_CLASS) return null
        val name = clazz.simpleName.asString()
        val scopes = clazz.annotations.mapNotNull { scopeOf(it, clazz) }.toList()
        if (clazz.qualifiedName?.asString() == mainClass.qualifiedName?.asString()) {
            if (scopes.isNotEmpty())
                fail(clazz, "$name is the plugin's main class and a local bean already; remove ${scopes.joinToString { "@" + it.declaration.simpleName.asString() }}")
            return null
        }
        val recorded = attributes.of(clazz, clazz)
        if (scopes.isEmpty()) {
            if (recorded.isPrimary()) fail(clazz, "@Primary on $name has no effect: the class is not a bean")
            if (recorded.qualifier() != null) fail(clazz, "@Qualifier on $name has no effect: the class is not a bean")
            return null
        }
        if (scopes.size > 1)
            return fail(clazz, "$name has more than one bean scope: ${scopes.joinToString { it.declaration.simpleName.asString() }}")
        val scope = scopes.single()

        if (clazz.classKind != ClassKind.CLASS || Modifier.ABSTRACT in clazz.modifiers || Modifier.INNER in clazz.modifiers)
            return fail(clazz, "$name must be a concrete, non-inner class to be a bean")
        if (clazz.typeParameters.isNotEmpty()) return fail(clazz, "$name must not have type parameters to be a bean")
        val constructor = clazz.primaryConstructor ?: clazz.getConstructors().singleOrNull()
            ?: return fail(clazz, "$name must have exactly one constructor to be a bean")

        val beanName = recorded.qualifier() ?: BeanNames.defaultFor(name)
        if (beanName.isEmpty()) return fail(clazz, "@Qualifier on $name must not be empty")

        val parameters = injectableParametersOf(constructor.parameters, attributes, ::fail) ?: return null

        return BeanDeclaration(
            type = clazz.toClassName(),
            name = beanName,
            primary = recorded.isPrimary(),
            scope = attributes.occurrence(scope.occurrence, clazz),
            enhancements = scope.enhancements,
            annotations = recorded,
            parameters = parameters.map { it.recorded },
            assignableTypes = clazz.assignableTypes(),
            reachable = clazz.isReachable() && constructor.isReachable() && parameters.allReachable(),
            node = clazz,
        )
    }

    private class Scope(val occurrence: KSAnnotation, val declaration: KSClassDeclaration, val enhancements: List<Enhancement>)

    private fun scopeOf(annotation: KSAnnotation, usage: KSNode): Scope? {
        val chain = enhancements.chainOf(annotation)
        if (chain.isEmpty() || !enhancements.inherits(chain, beanAnnotation)) return null
        val declaration = chain.first().declaration
        val resolved = enhancements.enhancementsOf(chain, usage)
        if (resolved.isEmpty()) {
            if (!enhancements.hadErrors)
                fail(usage, "${declaration.simpleName.asString()} inherits @Bean but no @VersatiaEnhancement names its processor")
            return null
        }
        resolved.firstOrNull { it.processorClass in BeanProcessors.SCOPES && it.phase != EventType.PLUGIN_START_ONLY }?.let {
            return fail(
                usage,
                "${declaration.simpleName.asString()} runs ${it.processorClass} at ${it.phase}; beans can only be declared at PLUGIN_START_ONLY",
            )
        }
        return Scope(annotation, declaration, resolved)
    }

    private fun fail(node: KSNode, message: String): Nothing? {
        failed = true
        logger.error(message, node)
        return null
    }
}
