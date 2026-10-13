package com.github.marcoral.versatia.util.lang

import kotlin.enums.enumEntries

inline fun <reified T : Enum<T>> asEnumIgnoreCase(name: String) =
    enumEntries<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }

inline fun <reified T : Enum<T>> asEnumIgnoreCaseOrThrow(name: String) =
    asEnumIgnoreCase<T>(name) ?: throw IllegalArgumentException(
        "No enum constant of ${T::class.qualifiedName} matches \"$name\" (ignoring case); " +
            "allowed: ${enumEntries<T>().joinToString { it.name }}",
    )

inline fun <reified T : Enum<T>> isEnumIgnoreCase(name: String) = asEnumIgnoreCase<T>(name) != null

/** Like [asEnumIgnoreCase] for an enum class known only at runtime. */
fun asEnumIgnoreCase(enumClass: Class<out Enum<*>>, name: String) = enumClass.enumConstants.firstOrNull { it.name.equals(name, ignoreCase = true) }
