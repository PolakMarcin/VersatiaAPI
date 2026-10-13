package com.github.marcoral.versatia.ksp.config

import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.config.Value
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.argument
import com.github.marcoral.versatia.ksp.classesIn
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.memberOwner
import com.github.marcoral.versatia.ksp.model.PropertyDeclaration
import com.github.marcoral.versatia.ksp.model.TypeDeclaration
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every property annotated with an enhancement (an annotation that inherits
 * `@VersatiaEnhancement`) and checks that the runtime will be able to set it: a `var` of the
 * plugin's main class, an `object` or a bean, or a `val` holding a `PreviousValueTracking`, whose
 * cell is set instead. A `@Value` property must also have a type the runtime can read, by itself
 * or through its mapper; see [ValueTypeCheck].
 */
internal class PropertyCollector(
    private val logger: KSPLogger,
    private val beanTypes: Set<ClassName>,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
) {
    private var failed = false

    val hadErrors get() = failed

    private val enhancementAnnotation = VersatiaEnhancement::class.qualifiedName!!
    private val valueAnnotation = Value::class.qualifiedName!!

    fun collect(files: List<KSFile>): List<PropertyDeclaration> {
        for (property in files.flatMap { it.declarations.filterIsInstance<KSPropertyDeclaration>() }) if (property.isEnhanced())
            fail(property, "${property.simpleName.asString()} cannot be enhanced: it is a top-level property; move it into the plugin's main class, an object or a bean")
        return files
            .flatMap { file -> file.declarations.flatMap(::classesIn) }
            .flatMap { clazz -> clazz.getDeclaredProperties().mapNotNull { describe(clazz, it) } }
    }

    private fun KSPropertyDeclaration.isEnhanced() = annotations.any { enhancements.inherits(enhancements.chainOf(it), enhancementAnnotation) }

    private fun describe(clazz: KSClassDeclaration, property: KSPropertyDeclaration): PropertyDeclaration? {
        val enhanced = property.annotations.mapNotNull { annotation ->
            val chain = enhancements.chainOf(annotation)
            if (chain.isEmpty() || !enhancements.inherits(chain, enhancementAnnotation)) return@mapNotNull null
            Triple(annotation, chain, enhancements.enhancementsOf(chain, property))
        }.toList()
        if (enhanced.isEmpty()) return null
        val name = "${clazz.simpleName.asString()}.${property.simpleName.asString()}"
        if (enhanced.size > 1)
            return fail(property, "$name carries more than one enhancement: ${enhanced.joinToString { it.first.shortName.asString() }}")
        val (annotation, chain, resolved) = enhanced.single()
        if (resolved.isEmpty()) return null

        val ownerKind = clazz.memberOwner(beanTypes) ?: return fail(
            property,
            "$name cannot be enhanced: ${clazz.simpleName.asString()} is neither the plugin's main class, an object nor a bean",
        )
        val type = property.type.resolve()
        val isValue = chain.any { it.declaration.qualifiedName?.asString() == valueAnnotation }
        val tracked = isValue && ValueTypeCheck.isTracked(type)
        when {
            !property.isMutable && !tracked -> return fail(property, "$name must be a var to be enhanced")
            property.extensionReceiver != null -> return fail(property, "$name must not be an extension property to be enhanced")
            Modifier.ABSTRACT in property.modifiers -> return fail(property, "$name must not be abstract to be enhanced")
        }
        if (isValue) ValueTypeCheck.problem(type, annotation.argument("mapper") as? KSType)?.let { return fail(property, "@Value on $name: $it") }
        val declared = type.declared() ?: return fail(property, "$name has a type the descriptor cannot record: $type")
        val accessorReachable = if (tracked) property.getterIsReachable() else property.setterIsReachable()

        return PropertyDeclaration(
            owner = clazz.toClassName(),
            ownerKind = ownerKind,
            name = property.simpleName.asString(),
            annotation = attributes.occurrence(annotation, property),
            enhancements = resolved,
            annotations = attributes.of(property, property),
            type = declared,
            reachable = clazz.isReachable() && property.isReachable() && accessorReachable && type.isReachable(),
            node = property,
            classAnnotations = attributes.of(clazz, property),
            via = if (tracked) TRACKED_CELL else null,
        )
    }

    private fun KSType.declared(): TypeDeclaration? {
        val clazz = declaration as? KSClassDeclaration ?: return null
        val arguments = this.arguments.map { it.type?.resolve()?.declared() ?: return null }
        return TypeDeclaration(clazz.toClassName(), arguments, isMarkedNullable)
    }

    private fun KSType.isReachable(): Boolean = declaration.isReachable() && arguments.all { it.type?.resolve()?.isReachable() == true }

    /** A `private set` or `protected set` hides the setter from generated code even when the property is visible. */
    private fun KSPropertyDeclaration.setterIsReachable() = setter?.modifiers?.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED } != false

    private fun KSPropertyDeclaration.getterIsReachable() = getter?.modifiers?.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED } != false

    private fun fail(node: KSNode, message: String): Nothing? {
        failed = true
        logger.error(message, node)
        return null
    }

    private companion object {
        /** The mutable property of a `PreviousValueTracking` the runtime stores the value in. */
        const val TRACKED_CELL = "value"
    }
}
