package com.github.marcoral.versatia.ksp.model

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.ksp.di.BeanDeclaration
import com.github.marcoral.versatia.ksp.di.PluginDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName

/** One `@VersatiaEnhancement` resolved for a concrete occurrence: which processor, at which phase. */
internal data class Enhancement(val processorClass: String, val phase: EventType)

/** An enhancement annotation as written at one place together with the enhancements it resolves to. */
internal data class EnhancedAnnotation(val annotation: AnnotationRef, val enhancements: List<Enhancement>)

/** An annotation as written at one place, with its arguments as descriptor values. */
internal data class AnnotationRef(val type: ClassName, val arguments: Map<String, Any?> = emptyMap())

/** A constructor or function parameter with the attributes recorded on it. */
internal data class ParameterDeclaration(val type: ClassName, val annotations: List<AnnotationRef> = emptyList())

/** A function carrying an enhancement, such as `@AutoInvoke`; its parameters are injected. */
internal data class FunctionDeclaration(
    val owner: ClassName,
    val ownerKind: FunctionOwner,
    val name: String,
    val annotation: AnnotationRef,
    val enhancements: List<Enhancement>,
    val annotations: List<AnnotationRef>,
    val parameters: List<ParameterDeclaration>,
    /** True when generated code in the plugin's package can call the function directly. */
    val reachable: Boolean,
    val node: KSNode? = null,
)

internal data class DescriptorModel(
    val packageName: String,
    val beans: List<BeanDeclaration>,
    val functions: List<FunctionDeclaration> = emptyList(),
    val plugin: PluginDeclaration? = null,
)
