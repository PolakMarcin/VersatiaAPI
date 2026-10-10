package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.di.BeanNames
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.ParameterDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName

/** A type to inject and, optionally, the name of the bean that must provide it. */
internal data class DependencyRef(val type: ClassName, val qualifier: String? = null) {
    override fun toString() = if (qualifier == null) type.canonicalName else "${type.canonicalName} named \"$qualifier\""
}

internal data class BeanDeclaration(
    val type: ClassName,
    val name: String,
    val primary: Boolean,
    /** The scope annotation as written on the class; the descriptor records it as the producing annotation. */
    val scope: AnnotationRef,
    val enhancements: List<Enhancement>,
    val annotations: List<AnnotationRef>,
    val parameters: List<ParameterDeclaration>,
    val assignableTypes: List<ClassName>,
    /** True when generated code in the plugin's package can call the constructor directly. */
    val reachable: Boolean,
    val node: KSNode? = null,
) {
    val dependencies get() = parameters.map { it.toDependency() }
}

/**
 * The plugin's main class with the enhancements written on it or on its supertypes. With
 * `@PluginBean` among them it is a local bean of its own plugin, always named `BeanNames.PLUGIN`,
 * whose instance Bukkit creates, so it has no dependencies and nothing to instantiate; it then
 * takes part in validation like any other bean.
 */
internal data class PluginDeclaration(
    val type: ClassName,
    val primary: Boolean,
    val annotations: List<AnnotationRef>,
    val assignableTypes: List<ClassName>,
    val enhancements: List<EnhancedAnnotation> = emptyList(),
    val node: KSNode? = null,
) {
    val isBean get() = enhancements.any { enhanced -> enhanced.enhancements.any { it.processorClass == BeanProcessors.PLUGIN } }

    fun asBean() = BeanDeclaration(
        type = type,
        name = BeanNames.PLUGIN,
        primary = primary,
        scope = AnnotationRef(type),
        enhancements = emptyList(),
        annotations = annotations,
        parameters = emptyList(),
        assignableTypes = assignableTypes,
        reachable = true,
        node = node,
    )
}
