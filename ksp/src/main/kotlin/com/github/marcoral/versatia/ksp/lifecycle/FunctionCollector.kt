package com.github.marcoral.versatia.ksp.lifecycle

import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.JvmSignatures
import com.github.marcoral.versatia.ksp.Owners
import com.github.marcoral.versatia.ksp.Reporter
import com.github.marcoral.versatia.ksp.Sources
import com.github.marcoral.versatia.ksp.di.Injection
import com.github.marcoral.versatia.ksp.di.allReachable
import com.github.marcoral.versatia.ksp.displayName
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.memberOwner
import com.github.marcoral.versatia.ksp.model.FunctionDeclaration
import com.github.marcoral.versatia.ksp.notAnOwner
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every member function annotated with an enhancement (an annotation that inherits
 * `@VersatiaEnhancement`) and checks that the runtime will be able to call it: the owner must be
 * the plugin's main class or a base class of it, an `object` or a bean, and the parameters must
 * be injectable. A top-level function has no owner at all.
 */
internal class FunctionCollector(
    private val reporter: Reporter,
    private val owners: Owners,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
    private val injection: Injection,
    private val jvm: JvmSignatures,
) {
    fun collect(sources: Sources): List<FunctionDeclaration> {
        for (function in sources.topLevelFunctions) if (enhancements.isEnhanced(function)) reporter.fail(
            function,
            "${function.simpleName.asString()}() cannot be enhanced: it is a top-level function; move it into the plugin's main class, an object or a bean",
        )
        return sources.classes.flatMap { clazz -> clazz.getDeclaredFunctions().mapNotNull { describe(clazz, it) } }
    }

    private fun describe(clazz: KSClassDeclaration, function: KSFunctionDeclaration): FunctionDeclaration? {
        val enhanced = enhancements.memberEnhancementsOf(function)
        if (enhanced.isEmpty()) return null
        val name = "${clazz.displayName()}.${function.simpleName.asString()}"
        if (enhanced.size > 1)
            return reporter.fail(function, "$name carries more than one enhancement: ${enhanced.joinToString { it.annotation.shortName.asString() }}")
        val single = enhanced.single()
        if (single.enhancements.isEmpty()) return null

        val ownerKind = clazz.memberOwner(owners) ?: return reporter.fail(function, "$name cannot be enhanced: ${clazz.notAnOwner()}")
        when {
            function.extensionReceiver != null -> return reporter.fail(function, "$name must not be an extension function to be enhanced")
            Modifier.SUSPEND in function.modifiers -> return reporter.fail(function, "$name must not be suspend to be enhanced")
            Modifier.ABSTRACT in function.modifiers -> return reporter.fail(function, "$name must not be abstract to be enhanced")
            function.typeParameters.isNotEmpty() -> return reporter.fail(function, "$name must not have type parameters to be enhanced")
        }
        val parameters = injection.parametersOf(function, "$name()") ?: return null

        return FunctionDeclaration(
            owner = clazz.toClassName(),
            ownerKind = ownerKind,
            name = function.simpleName.asString(),
            annotation = attributes.occurrence(single.annotation, function),
            enhancements = single.enhancements,
            annotations = attributes.of(function, function),
            parameters = parameters.map { it.recorded },
            reachable = clazz.isReachable() && function.isReachable() && parameters.allReachable(),
            node = function,
            classAnnotations = attributes.of(clazz, function),
            jvmName = jvm.nameOf(function),
        )
    }
}
