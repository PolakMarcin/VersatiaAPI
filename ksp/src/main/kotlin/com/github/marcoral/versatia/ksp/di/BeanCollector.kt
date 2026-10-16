package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.di.BeanNames
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.Reporter
import com.github.marcoral.versatia.ksp.Sources
import com.github.marcoral.versatia.ksp.assignableTypes
import com.github.marcoral.versatia.ksp.isAbstract
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every class annotated with a scope that inherits `@Bean` and describes it. The plugin's
 * main class is described separately, with the enhancements the generic `PluginCollector` found.
 */
internal class BeanCollector(
    private val reporter: Reporter,
    private val mainClass: KSClassDeclaration,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
    private val injection: Injection,
) {
    private val beanAnnotation = Bean::class.qualifiedName!!

    /**
     * The qualified names of every class carrying a bean scope, valid or not: the members of a
     * rejected bean are not reported a second time as belonging to no bean.
     */
    var scopedTypes = emptySet<String>()
        private set

    fun collect(sources: Sources): List<BeanDeclaration> {
        val scoped = mutableSetOf<String>()
        val beans = sources.classes.mapNotNull { describe(it, scoped) }
        scopedTypes = scoped
        return beans
    }

    /** The main class: its bean is always named `BeanNames.PLUGIN`, so `@Qualifier` is rejected; `@Primary` applies. */
    fun describePlugin(enhancements: List<EnhancedAnnotation>): PluginDeclaration {
        val recorded = attributes.of(mainClass, mainClass)
        if (recorded.qualifier() != null)
            reporter.fail(mainClass, "${mainClass.simpleName.asString()} is the plugin's main class and its bean is always named \"${BeanNames.PLUGIN}\"; remove @Qualifier")
        return PluginDeclaration(
            type = mainClass.toClassName(),
            primary = recorded.isPrimary(),
            annotations = recorded,
            assignableTypes = mainClass.assignableTypes(),
            enhancements = enhancements,
            node = mainClass,
        )
    }

    private fun describe(clazz: KSClassDeclaration, scoped: MutableSet<String>): BeanDeclaration? {
        // An annotation class carrying a scope defines a new scope, it is not a bean
        if (clazz.classKind == ClassKind.ANNOTATION_CLASS) return null
        val name = clazz.simpleName.asString()
        val scopes = clazz.annotations.mapNotNull { scopeOf(it, clazz) }.toList()
        if (clazz.qualifiedName?.asString() == mainClass.qualifiedName?.asString()) {
            if (scopes.isNotEmpty())
                reporter.fail(clazz, "$name is the plugin's main class and a local bean already; remove ${scopes.joinToString { "@" + it.declaration.simpleName.asString() }}")
            return null
        }
        if (clazz.annotations.any { enhancements.inherits(enhancements.chainOf(it), beanAnnotation) }) clazz.qualifiedName?.asString()?.let { scoped += it }
        val recorded = attributes.of(clazz, clazz)
        if (scopes.isEmpty()) {
            if (recorded.isPrimary()) reporter.fail(clazz, "@Primary on $name has no effect: the class is not a bean")
            if (recorded.qualifier() != null) reporter.fail(clazz, "@Qualifier on $name has no effect: the class is not a bean")
            return null
        }
        if (scopes.size > 1)
            return reporter.fail(clazz, "$name has more than one bean scope: ${scopes.joinToString { it.declaration.simpleName.asString() }}")
        val scope = scopes.single()

        clazz.notABean()?.let { return reporter.fail(clazz, "$name cannot be a bean: it $it") }
        if (clazz.typeParameters.isNotEmpty()) return reporter.fail(clazz, "$name must not have type parameters to be a bean")
        val constructor = clazz.injectedConstructor()
            ?: return reporter.fail(clazz, "$name must have a primary constructor or exactly one constructor to be a bean")

        val qualifier = recorded.qualifier()
        if (qualifier?.isBlank() == true) return reporter.fail(clazz, "@Qualifier on $name must not be blank")
        val described = injection.instantiable(clazz, constructor) ?: return null

        return BeanDeclaration(
            type = described.type,
            name = qualifier ?: BeanNames.defaultFor(name),
            primary = recorded.isPrimary(),
            scope = attributes.occurrence(scope.occurrence, clazz),
            enhancements = scope.enhancements,
            annotations = described.annotations,
            parameters = described.parameters,
            assignableTypes = described.assignableTypes,
            reachable = described.reachable,
            node = clazz,
        )
    }

    private fun KSClassDeclaration.notABean() = when {
        classKind == ClassKind.OBJECT -> "is an object, which is its own single instance; use it directly"
        classKind == ClassKind.INTERFACE -> "is an interface; annotate a class implementing it"
        classKind == ClassKind.ENUM_CLASS -> "is an enum class, whose instances are its constants"
        classKind != ClassKind.CLASS -> "is not a class"
        isAbstract() -> "is abstract; annotate a concrete subclass"
        Modifier.INNER in modifiers -> "is an inner class, which needs an instance of its outer class; make it a nested class"
        else -> null
    }

    private class Scope(val occurrence: KSAnnotation, val declaration: KSClassDeclaration, val enhancements: List<Enhancement>)

    private fun scopeOf(annotation: KSAnnotation, usage: KSNode): Scope? {
        val chain = enhancements.chainOf(annotation)
        if (chain.isEmpty() || !enhancements.inherits(chain, beanAnnotation)) return null
        val declaration = chain.first().declaration
        val resolved = enhancements.enhancementsOf(chain, usage)
        if (resolved.isEmpty()) {
            // A declaration that fails to resolve has been reported by the resolver
            if (!enhancements.declaresEnhancement(chain))
                reporter.fail(usage, "${declaration.simpleName.asString()} inherits @Bean but no @VersatiaEnhancement names its processor")
            return null
        }
        resolved.firstOrNull { it.processorClass in BeanProcessors.SCOPES && it.phase != Phase.PLUGIN_START_ONLY }?.let {
            return reporter.fail(
                usage,
                "${declaration.simpleName.asString()} runs ${it.processorClass} at ${it.phase}; beans can only be declared at PLUGIN_START_ONLY",
            )
        }
        return Scope(annotation, declaration, resolved)
    }
}
