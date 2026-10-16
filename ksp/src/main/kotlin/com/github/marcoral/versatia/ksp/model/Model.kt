package com.github.marcoral.versatia.ksp.model

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.ksp.di.BeanDeclaration
import com.github.marcoral.versatia.ksp.di.PluginDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/** One `@VersatiaEnhancement` resolved for a concrete occurrence: which processor, at which phase. */
internal data class Enhancement(val processorClass: String, val phase: EventType)

/** One `@VersatiaSubclassEnhancement`: which processor, at which phase, for the subclasses of which type. */
internal data class SubclassEnhancement(val processorClass: String, val phase: EventType, val subclassOf: ClassName)

/**
 * An enhancement annotation as written on the main class or one of its supertypes together with
 * what it resolves to: the enhancements of the main class and the subclass enhancements it
 * declares. [origin] is the class the annotation is written on.
 */
internal data class EnhancedAnnotation(
    val annotation: AnnotationRef,
    val enhancements: List<Enhancement>,
    val subclassEnhancements: List<SubclassEnhancement> = emptyList(),
    val origin: ClassName,
)

/** An annotation as written at one place, with its arguments as descriptor values. */
internal data class AnnotationRef(val type: ClassName, val arguments: Map<String, Any?> = emptyMap())

/**
 * A constructor or function parameter with the attributes recorded on it, its type as
 * `Class.getName` reports it and as generated code casts to it, whether it has a default value,
 * and the processor that supplies its argument instead of a bean, if any.
 */
internal data class ParameterDeclaration(
    val type: ClassName,
    val annotations: List<AnnotationRef> = emptyList(),
    val jvmType: String = type.reflectionName(),
    val typeName: TypeName = type,
    val optional: Boolean = false,
    val suppliedBy: String? = null,
)

/** A type as written on a property: the facts a `TypeReference` records, and the Kotlin type the generated code casts to. */
internal data class TypeDeclaration(val type: ClassName, val arguments: List<TypeDeclaration> = emptyList(), val nullable: Boolean = false) {
    fun asTypeName(): TypeName = (if (arguments.isEmpty()) type else type.parameterizedBy(arguments.map { it.asTypeName() })).copy(nullable = nullable)
}

/** A class the runtime may instantiate: the facts a `ClassTarget` records. */
internal data class ClassDeclaration(
    val type: ClassName,
    val annotations: List<AnnotationRef>,
    val parameters: List<ParameterDeclaration>,
    val assignableTypes: List<ClassName>,
    /** True when generated code in the plugin's package can call the constructor (or name the object) directly. */
    val reachable: Boolean,
    val isObject: Boolean = false,
    val node: KSNode? = null,
)

/** A class matched by a subclass enhancement; [clazz] is null when the match is the plugin's main class. */
internal data class SubclassElement(
    val origin: ClassName,
    val annotation: AnnotationRef,
    val enhancement: Enhancement,
    val clazz: ClassDeclaration?,
)

/** A function carrying an enhancement, such as `@AutoInvoke`; its parameters are injected. */
internal data class FunctionDeclaration(
    val owner: ClassName,
    val ownerKind: MemberOwner,
    val name: String,
    val annotation: AnnotationRef,
    val enhancements: List<Enhancement>,
    val annotations: List<AnnotationRef>,
    val parameters: List<ParameterDeclaration>,
    /** True when generated code in the plugin's package can call the function directly. */
    val reachable: Boolean,
    val node: KSNode? = null,
    /** The attributes written on the declaring class. */
    val classAnnotations: List<AnnotationRef> = emptyList(),
    /** The method name the JVM knows, which differs for `internal` functions, `@JvmName` and value-class parameters. */
    val jvmName: String = name,
)

/**
 * A property carrying an enhancement, such as `@Value`: a `var` the runtime sets, or a `val` whose
 * held object the runtime stores into through [via].
 */
internal data class PropertyDeclaration(
    val owner: ClassName,
    val ownerKind: MemberOwner,
    val name: String,
    val annotation: AnnotationRef,
    val enhancements: List<Enhancement>,
    val annotations: List<AnnotationRef>,
    val type: TypeDeclaration,
    /** True when generated code in the plugin's package can assign the property directly. */
    val reachable: Boolean,
    val node: KSNode? = null,
    /** The attributes written on the declaring class. */
    val classAnnotations: List<AnnotationRef> = emptyList(),
    /** The mutable property of the object the property holds that receives the value, when the property itself is not assigned. */
    val via: String? = null,
    /** The index of the bean's constructor parameter the property is declared by, if any. */
    val constructorParameter: Int? = null,
) {
    /** The type of the value the setter stores: the property's own, or the one its held cell takes. */
    val stored get() = if (via == null) type else type.arguments.single()
}

internal data class DescriptorModel(
    val packageName: String,
    val beans: List<BeanDeclaration>,
    val functions: List<FunctionDeclaration> = emptyList(),
    val plugin: PluginDeclaration? = null,
    val subclasses: List<SubclassElement> = emptyList(),
    val properties: List<PropertyDeclaration> = emptyList(),
    /** Simple names declared in [packageName] that would shadow the default imports of the generated file. */
    val shadowedNames: Set<String> = emptySet(),
)
