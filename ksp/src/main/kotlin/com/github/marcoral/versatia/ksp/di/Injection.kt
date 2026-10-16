package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.config.Value
import com.github.marcoral.versatia.di.Primary
import com.github.marcoral.versatia.di.Qualifier
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.JvmSignatures
import com.github.marcoral.versatia.ksp.Reporter
import com.github.marcoral.versatia.ksp.assignableTypes
import com.github.marcoral.versatia.ksp.declaration
import com.github.marcoral.versatia.ksp.declared
import com.github.marcoral.versatia.ksp.displayName
import com.github.marcoral.versatia.ksp.expanded
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.ParameterDeclaration
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName

internal val QUALIFIER = Qualifier::class.asClassName()
internal val PRIMARY = Primary::class.asClassName()

/** The value of `@Qualifier` among recorded attributes, or null when absent. */
internal fun List<AnnotationRef>.qualifier() = firstOrNull { it.type == QUALIFIER }?.arguments?.get("value") as? String

internal fun List<AnnotationRef>.isPrimary() = any { it.type == PRIMARY }

internal fun ParameterDeclaration.toDependency() = DependencyRef(type, annotations.qualifier(), optional)

/** An injectable parameter: whether generated code can name its type, and what the descriptor records. */
internal class InjectedParameter(val reachable: Boolean, val recorded: ParameterDeclaration)

internal fun List<InjectedParameter>.allReachable() = all { it.reachable }

/** The constructor the runtime calls: the primary one, or else the only one; null when there is neither. */
internal fun KSClassDeclaration.injectedConstructor() = primaryConstructor ?: getConstructors().singleOrNull()

/**
 * Turns constructor and function parameters into dependencies the runtime injects; a constructor
 * parameter with a default value is optional, and one carrying `@Value`, on the parameter or on
 * the property it declares, is supplied by its processor instead. See docs/enhancements.md.
 */
internal class Injection(private val attributes: Attributes, private val reporter: Reporter, private val jvm: JvmSignatures) {
    private val valueAnnotation = Value::class.qualifiedName!!

    /** The parameters of [function], declared in [owner], or null after reporting one the runtime could not satisfy. */
    fun parametersOf(function: KSFunctionDeclaration, owner: String, constructor: Boolean = false): List<InjectedParameter>? {
        val jvmTypes = jvm.parameterTypesOf(function)?.takeIf { it.size == function.parameters.size }
        return function.parameters.mapIndexed { index, parameter -> parameterOf(parameter, owner, jvmTypes?.get(index), constructor) ?: return null }
    }

    /** The facts of a class the runtime creates through [constructor], or null after a reported failure. */
    fun instantiable(clazz: KSClassDeclaration, constructor: KSFunctionDeclaration): ClassDeclaration? {
        val parameters = parametersOf(constructor, clazz.displayName(), constructor = true) ?: return null
        return ClassDeclaration(
            type = clazz.toClassName(),
            annotations = attributes.of(clazz, clazz),
            parameters = parameters.map { it.recorded },
            assignableTypes = clazz.assignableTypes(),
            reachable = clazz.isReachable() && constructor.isReachable() && parameters.allReachable(),
            node = clazz,
        )
    }

    private fun parameterOf(parameter: KSValueParameter, owner: String, jvmType: String?, constructor: Boolean): InjectedParameter? {
        val name = parameter.name?.asString()
        val label = "Injected parameter $name of $owner"
        val type = parameter.type.resolve().expanded()
        val declaration = type.declaration as? KSClassDeclaration
        val recorded = attributes.of(parameter, parameter)
        if (constructor && (parameter.hasValue() || parameter.declaredProperty()?.hasValue() == true)) return supplied(type, recorded, jvmType)
        return when {
            parameter.isVararg -> reporter.fail(parameter, "$label must not be vararg")
            type.isMarkedNullable -> reporter.fail(parameter, "$label must not be nullable")
            type.isFunctionType || type.isSuspendFunctionType -> reporter.fail(parameter, "$label must not be a function type")
            declaration == null -> reporter.fail(parameter, "$label must be a class type")
            type.arguments.isNotEmpty() -> reporter.fail(parameter, "$label must not be generic")
            recorded.qualifier()?.isBlank() == true -> reporter.fail(parameter, "@Qualifier on parameter $name of $owner must not be blank")
            else -> {
                val className = declaration.toClassName()
                val optional = constructor && parameter.hasDefault
                InjectedParameter(declaration.isReachable(), ParameterDeclaration(className, recorded, jvmType ?: className.reflectionName(), optional = optional))
            }
        }
    }

    private fun KSAnnotated.hasValue() = annotations.any { it.declaration()?.qualifiedName?.asString() == valueAnnotation }

    /** The property a constructor parameter declares, which alone carries an annotation written with `@property:`. */
    private fun KSValueParameter.declaredProperty() = if (!isVal && !isVar) null
    else ((parent as? KSFunctionDeclaration)?.parentDeclaration as? KSClassDeclaration)?.getDeclaredProperties()
        ?.firstOrNull { it.simpleName.asString() == name?.asString() }

    /** A `@Value` constructor parameter, whose type `PropertyCollector` checks with the property it declares. */
    private fun supplied(type: KSType, recorded: List<AnnotationRef>, jvmType: String?): InjectedParameter {
        val className = (type.declaration as? KSClassDeclaration)?.toClassName() ?: ANY
        val typeName = type.declared()?.asTypeName() ?: className
        return InjectedParameter(type.isReachable(), ParameterDeclaration(className, recorded, jvmType ?: className.reflectionName(), typeName, suppliedBy = ConfigProcessors.VALUE))
    }
}
