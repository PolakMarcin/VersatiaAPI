package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.di.Primary
import com.github.marcoral.versatia.di.Qualifier
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.github.marcoral.versatia.ksp.model.ParameterDeclaration
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName

internal val QUALIFIER = Qualifier::class.asClassName()
internal val PRIMARY = Primary::class.asClassName()

/** The value of `@Qualifier` among recorded attributes, or null when absent. */
internal fun List<AnnotationRef>.qualifier() = firstOrNull { it.type == QUALIFIER }?.arguments?.get("value") as? String

internal fun List<AnnotationRef>.isPrimary() = any { it.type == PRIMARY }

internal fun ParameterDeclaration.toDependency() = DependencyRef(type, annotations.qualifier())

/** An injectable parameter: its declaration, for reachability, and what the descriptor records. */
internal class InjectedParameter(val declaration: KSClassDeclaration, val recorded: ParameterDeclaration)

/**
 * Turns constructor or function parameters into injectable dependencies, reporting through [fail]
 * any parameter the runtime could not satisfy. Returns null after a failure.
 */
internal fun injectableParametersOf(
    parameters: List<KSValueParameter>,
    attributes: Attributes,
    fail: (KSNode, String) -> Nothing?,
): List<InjectedParameter>? =
    parameters.map { parameter ->
        val name = parameter.name?.asString()
        val type = parameter.type.resolve()
        val declaration = type.declaration as? KSClassDeclaration
        val recorded = attributes.of(parameter, parameter)
        when {
            parameter.isVararg -> return fail(parameter, "Injected parameter $name must not be vararg")
            type.isMarkedNullable -> return fail(parameter, "Injected parameter $name must not be nullable")
            declaration == null -> return fail(parameter, "Injected parameter $name must be a class type")
            type.arguments.isNotEmpty() -> return fail(parameter, "Injected parameter $name must not be generic")
            recorded.qualifier() == "" -> return fail(parameter, "@Qualifier on parameter $name must not be empty")
            else -> InjectedParameter(declaration, ParameterDeclaration(declaration.toClassName(), recorded))
        }
    }

internal fun List<InjectedParameter>.allReachable() = all { it.declaration.isReachable() }
