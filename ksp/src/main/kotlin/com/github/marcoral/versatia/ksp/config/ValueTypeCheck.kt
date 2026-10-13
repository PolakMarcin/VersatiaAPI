package com.github.marcoral.versatia.ksp.config

import com.github.marcoral.versatia.config.ValueTypes
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/**
 * Checks what a `@Value` property can be read into: a type of [ValueTypes] when no mapper is
 * named, otherwise a `ValueMapper` the runtime can create whose result the property can hold; a
 * `PreviousValueTracking<T>` is checked by its `T`. Names, not classes: the mapper types refer to
 * the Paper API, which the processor's classpath lacks.
 */
internal object ValueTypeCheck {
    private const val SUPPORTED = "Boolean, Int, Long, Float, Double, UInt, ULong, String, Percent, an enum, Color, Location, OfflinePlayer, " +
        "Vector, ItemStack, ConfigurationSection, a List or Set of the scalars and enums, a Map from String to them, and any of these nullable; " +
        "any other type needs a mapper"
    private const val VALUE_MAPPER = "com.github.marcoral.versatia.config.ValueMapper"
    private const val TRACKING = "com.github.marcoral.versatia.config.PreviousValueTracking"
    internal const val NO_MAPPER = "com.github.marcoral.versatia.config.NoMapper"

    /** Whether [type] is a `PreviousValueTracking`, whose cell the runtime fills instead of the property. */
    fun isTracked(type: KSType) = type.declaration.qualifiedName?.asString() == TRACKING

    /** Why [type] cannot hold a configuration value, read by [mapper] when one is named, or null when it can. */
    fun problem(type: KSType, mapper: KSType?): String? {
        if (isTracked(type)) {
            if (type.isMarkedNullable) return "a PreviousValueTracking must not be nullable; the runtime fills the cell the property holds"
            val held = type.arguments.singleOrNull()?.type?.resolve() ?: return "the value held by $type is not known"
            return problem(held, mapper)
        }
        val mapperClass = mapper?.declaration as? KSClassDeclaration
        return if (mapperClass == null || mapperClass.qualifiedName?.asString() == NO_MAPPER) builtInProblem(type) else mapperProblem(type, mapperClass)
    }

    private fun builtInProblem(type: KSType): String? {
        val name = type.declaration.qualifiedName?.asString() ?: return "$type is not a class type"
        val arguments = type.arguments.map { it.type?.resolve() }
        return when {
            name == ValueTypes.LIST || name == ValueTypes.SET -> elementProblem(arguments.singleOrNull(), "the elements of $type")
            name == ValueTypes.MAP -> keyProblem(arguments.firstOrNull(), type) ?: elementProblem(arguments.getOrNull(1), "the values of $type")
            type.isScalar() || name in ValueTypes.BUKKIT -> null
            else -> "$type is not a supported type; supported are $SUPPORTED"
        }
    }

    private fun mapperProblem(type: KSType, mapper: KSClassDeclaration): String? {
        val name = mapper.simpleName.asString()
        val creatable = mapper.classKind == ClassKind.OBJECT && !mapper.isCompanionObject ||
            mapper.classKind == ClassKind.CLASS && Modifier.ABSTRACT !in mapper.modifiers && mapper.getConstructors().any { c -> c.parameters.all { it.hasDefault } }
        if (!creatable) return "mapper $name must be an object or a class with a no-argument constructor"
        val mapped = mapper.getAllSuperTypes().firstOrNull { it.declaration.qualifiedName?.asString() == VALUE_MAPPER }?.arguments?.firstOrNull()?.type?.resolve()
            ?: return "mapper $name does not implement ValueMapper"
        return if (type.makeNotNullable().isAssignableFrom(mapped)) null else "mapper $name maps to $mapped, which $type cannot hold"
    }

    private fun elementProblem(element: KSType?, what: String) = when {
        element == null -> "$what are not known"
        element.isMarkedNullable -> "$what must not be nullable"
        !element.isScalar() -> "$what must be scalars or enums, not $element; supported are $SUPPORTED"
        else -> null
    }

    private fun keyProblem(key: KSType?, type: KSType) =
        if (key != null && !key.isMarkedNullable && key.declaration.qualifiedName?.asString() == STRING) null else "the keys of $type must be String"

    private fun KSType.isScalar() =
        declaration.qualifiedName?.asString() in ValueTypes.SCALARS || (declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

    private val STRING = String::class.qualifiedName!!
}
