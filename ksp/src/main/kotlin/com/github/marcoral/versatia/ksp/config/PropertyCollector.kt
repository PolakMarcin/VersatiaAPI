package com.github.marcoral.versatia.ksp.config

import com.github.marcoral.versatia.config.Value
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.ksp.Attributes
import com.github.marcoral.versatia.ksp.EnhancementResolver
import com.github.marcoral.versatia.ksp.Owners
import com.github.marcoral.versatia.ksp.Reporter
import com.github.marcoral.versatia.ksp.Sources
import com.github.marcoral.versatia.ksp.argument
import com.github.marcoral.versatia.ksp.declared
import com.github.marcoral.versatia.ksp.displayName
import com.github.marcoral.versatia.ksp.expanded
import com.github.marcoral.versatia.ksp.isReachable
import com.github.marcoral.versatia.ksp.memberOwner
import com.github.marcoral.versatia.ksp.model.PropertyDeclaration
import com.github.marcoral.versatia.ksp.notAnOwner
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Finds every property annotated with an enhancement (an annotation that inherits
 * `@VersatiaEnhancement`) and checks that the runtime will be able to set it: a `var` of the
 * plugin's main class, an `object` or a bean, or a `val` holding a `PreviousValueTracking`, whose
 * cell is set instead. A `@Value` property must also name a usable key and file and have a type
 * the runtime can read, by itself or through its mapper; see [ValueTypeCheck]. A property declared
 * in the primary constructor of a bean records that constructor parameter, which the runtime
 * passes the value to.
 */
internal class PropertyCollector(
    private val reporter: Reporter,
    private val owners: Owners,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
) {
    private val valueAnnotation = Value::class.qualifiedName!!

    fun collect(sources: Sources): List<PropertyDeclaration> {
        for (property in sources.topLevelProperties) if (enhancements.isEnhanced(property)) reporter.fail(
            property,
            "${property.simpleName.asString()} cannot be enhanced: it is a top-level property; move it into the plugin's main class, an object or a bean",
        )
        for (clazz in sources.classes) checkParameters(clazz)
        for (function in sources.topLevelFunctions) checkParameters(function, "${function.simpleName.asString()}()")
        return sources.classes.flatMap { clazz -> clazz.getDeclaredProperties().mapNotNull { describe(clazz, it) } }
    }

    /** A parameter is no element: only a constructor parameter that declares a property can be enhanced, through the property. */
    private fun checkParameters(clazz: KSClassDeclaration) {
        for (constructor in clazz.getConstructors()) for (parameter in constructor.parameters)
            if (!parameter.isVal && !parameter.isVar && enhancements.isEnhanced(parameter))
                reporter.fail(parameter, "${clazz.displayName()}.${parameter.name?.asString()} must be a var to be enhanced; declare the constructor parameter as a property")
        for (function in clazz.getDeclaredFunctions().filterNot { it.isConstructor() }) checkParameters(function, "${clazz.displayName()}.${function.simpleName.asString()}()")
    }

    private fun checkParameters(function: KSFunctionDeclaration, label: String) {
        for (parameter in function.parameters) if (enhancements.isEnhanced(parameter))
            reporter.fail(parameter, "Parameter ${parameter.name?.asString()} of $label cannot be enhanced; only properties can, constructor properties included")
    }

    private fun describe(clazz: KSClassDeclaration, property: KSPropertyDeclaration): PropertyDeclaration? {
        val enhanced = enhancements.memberEnhancementsOf(property)
        if (enhanced.isEmpty()) return null
        val name = "${clazz.displayName()}.${property.simpleName.asString()}"
        if (enhanced.size > 1)
            return reporter.fail(property, "$name carries more than one enhancement: ${enhanced.joinToString { it.annotation.shortName.asString() }}")
        val single = enhanced.single()
        if (single.enhancements.isEmpty()) return null
        enhancements.enhancedOverrideeOf(property)?.let {
            return reporter.fail(property, "$name overrides ${it.displayName()}, which is enhanced too, so the override would run for both; keep the enhancement on one of them")
        }

        val ownerKind = clazz.memberOwner(owners) ?: return reporter.fail(property, "$name cannot be enhanced: ${clazz.notAnOwner()}")
        val type = property.type.resolve().expanded()
        val isValue = single.chain.any { it.declaration.qualifiedName?.asString() == valueAnnotation }
        val tracked = isValue && ValueTypeCheck.isTracked(type)
        when {
            !property.isMutable && !tracked -> return reporter.fail(property, "$name must be a var to be enhanced")
            property.extensionReceiver != null -> return reporter.fail(property, "$name must not be an extension property to be enhanced")
            Modifier.ABSTRACT in property.modifiers -> return reporter.fail(property, "$name must not be abstract to be enhanced")
        }
        if (isValue) valueProblem(single.annotation, type)?.let { return reporter.fail(property, "@Value on $name: $it") }
        val declared = type.declared() ?: return reporter.fail(property, "$name has a type the descriptor cannot record: $type")
        val accessorReachable = if (tracked) property.getterIsReachable() else property.setterIsReachable()

        return PropertyDeclaration(
            owner = clazz.toClassName(),
            ownerKind = ownerKind,
            name = property.simpleName.asString(),
            annotation = attributes.occurrence(single.annotation, property),
            enhancements = single.enhancements,
            annotations = attributes.of(property, property),
            type = declared,
            reachable = clazz.isReachable() && property.isReachable() && accessorReachable && type.isReachable(),
            node = property,
            classAnnotations = attributes.of(clazz, property),
            via = if (tracked) TRACKED_CELL else null,
            constructorParameter = if (ownerKind == MemberOwner.INSTANCE) clazz.constructorIndexOf(property) else null,
        )
    }

    private fun valueProblem(annotation: KSAnnotation, type: KSType) =
        keyProblem(annotation.argument("key") as? String) ?: fileProblem(annotation.argument("file") as? String)
            ?: ValueTypeCheck.problem(type, (annotation.argument("mapper") as? KSType)?.expanded())

    private fun keyProblem(key: String?) = when {
        key.isNullOrBlank() -> "the key must not be blank"
        key.trim() != key -> "the key \"$key\" must not start or end with whitespace"
        key.split('.').any { it.isEmpty() } -> "the key \"$key\" has an empty segment; separate the parts of a path with single dots"
        else -> null
    }

    private fun fileProblem(file: String?) = when {
        file.isNullOrBlank() -> "the file must not be blank"
        file.trim() != file -> "the file \"$file\" must not start or end with whitespace"
        file.startsWith('/') || file.startsWith('\\') || DRIVE.containsMatchIn(file) -> "the file \"$file\" must be a path relative to the data folder"
        file.split('/', '\\').any { it == ".." } -> "the file \"$file\" must stay inside the data folder; remove the \"..\" segment"
        YAML_EXTENSIONS.none { file.endsWith(it, ignoreCase = true) } -> "the file \"$file\" must be a YAML file ending in .yml or .yaml"
        else -> null
    }

    /** The index of the primary-constructor parameter declaring [property], or null when the property is declared in the body. */
    private fun KSClassDeclaration.constructorIndexOf(property: KSPropertyDeclaration) = primaryConstructor?.parameters
        ?.indexOfFirst { (it.isVal || it.isVar) && it.name?.asString() == property.simpleName.asString() }
        ?.takeIf { it >= 0 }

    /** A `private set` or `protected set` hides the setter from generated code even when the property is visible. */
    private fun KSPropertyDeclaration.setterIsReachable() = setter?.modifiers?.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED } != false

    private fun KSPropertyDeclaration.getterIsReachable() = getter?.modifiers?.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED } != false

    private companion object {
        /** The mutable property of a `PreviousValueTracking` the runtime stores the value in. */
        const val TRACKED_CELL = "value"
        val YAML_EXTENSIONS = listOf(".yml", ".yaml")
        val DRIVE = Regex("^[A-Za-z]:")
    }
}
