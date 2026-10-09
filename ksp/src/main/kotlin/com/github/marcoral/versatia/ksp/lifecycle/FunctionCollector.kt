package com.github.marcoral.versatia.ksp.lifecycle

import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.classesIn
import com.github.marcoral.versatia.ksp.di.allReachable
import com.github.marcoral.versatia.ksp.di.injectableParametersOf
import com.github.marcoral.versatia.ksp.extendsJavaPlugin
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.model.FunctionDeclaration
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every member function annotated with an enhancement (an annotation that inherits
 * `@VersatiaEnhancement`) and checks that the runtime will be able to call it: the owner must be
 * the plugin's main class, an `object` or a bean, and the parameters must be injectable.
 */
internal class FunctionCollector(
    private val logger: KSPLogger,
    private val beanTypes: Set<ClassName>,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
) {
    private var failed = false

    val hadErrors get() = failed

    private val enhancementAnnotation = VersatiaEnhancement::class.qualifiedName!!

    fun collect(files: List<KSFile>) = files
        .flatMap { file -> file.declarations.flatMap(::classesIn) }
        .flatMap { clazz -> clazz.getDeclaredFunctions().mapNotNull { describe(clazz, it) } }

    private fun describe(clazz: KSClassDeclaration, function: KSFunctionDeclaration): FunctionDeclaration? {
        val enhanced = function.annotations.mapNotNull { annotation ->
            val chain = enhancements.chainOf(annotation)
            if (chain.isEmpty() || !enhancements.inherits(chain, enhancementAnnotation)) return@mapNotNull null
            annotation to enhancements.enhancementsOf(chain, function)
        }.toList()
        if (enhanced.isEmpty()) return null
        val name = "${clazz.simpleName.asString()}.${function.simpleName.asString()}"
        if (enhanced.size > 1)
            return fail(function, "$name carries more than one enhancement: ${enhanced.joinToString { it.first.shortName.asString() }}")
        val (annotation, resolved) = enhanced.single()
        if (resolved.isEmpty()) return null

        val ownerKind = ownerKindOf(clazz) ?: return fail(
            function,
            "$name cannot be enhanced: ${clazz.simpleName.asString()} is neither the plugin's main class, an object nor a bean",
        )
        when {
            function.extensionReceiver != null -> return fail(function, "$name must not be an extension function to be enhanced")
            Modifier.SUSPEND in function.modifiers -> return fail(function, "$name must not be suspend to be enhanced")
            Modifier.ABSTRACT in function.modifiers -> return fail(function, "$name must not be abstract to be enhanced")
            function.typeParameters.isNotEmpty() -> return fail(function, "$name must not have type parameters to be enhanced")
        }
        val parameters = injectableParametersOf(function.parameters, attributes, ::fail) ?: return null

        return FunctionDeclaration(
            owner = clazz.toClassName(),
            ownerKind = ownerKind,
            name = function.simpleName.asString(),
            annotation = attributes.occurrence(annotation, function),
            enhancements = resolved,
            annotations = attributes.of(function, function),
            parameters = parameters.map { it.recorded },
            reachable = clazz.isReachable() && function.isReachable() && parameters.allReachable(),
            node = function,
        )
    }

    private fun ownerKindOf(clazz: KSClassDeclaration) = when {
        clazz.classKind == ClassKind.OBJECT && !clazz.isCompanionObject -> FunctionOwner.OBJECT
        clazz.classKind == ClassKind.CLASS && clazz.extendsJavaPlugin() -> FunctionOwner.PLUGIN
        clazz.toClassName() in beanTypes -> FunctionOwner.INSTANCE
        else -> null
    }

    private fun fail(node: KSNode, message: String): Nothing? {
        failed = true
        logger.error(message, node)
        return null
    }
}
