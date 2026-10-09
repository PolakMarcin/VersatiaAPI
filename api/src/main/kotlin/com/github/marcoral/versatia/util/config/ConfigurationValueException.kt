package com.github.marcoral.versatia.util.config

import org.bukkit.configuration.ConfigurationSection

/** A required configuration value is missing or has the wrong shape. The message names the full key path. */
class ConfigurationValueException(message: String) : RuntimeException(message)

/** Full dotted path of [key] inside this section, as it appears in the file. */
fun ConfigurationSection.pathOf(key: String) = if (currentPath.isNullOrEmpty()) key else "$currentPath.$key"

@PublishedApi
internal fun ConfigurationSection.missingValue(key: String, message: String?) =
    ConfigurationValueException(message ?: "No value present at \"${pathOf(key)}\"")

@PublishedApi
internal fun ConfigurationSection.invalidValue(key: String, expected: String, message: String?) =
    ConfigurationValueException(message ?: "Value at \"${pathOf(key)}\" is not $expected: \"${get(key)}\"")

@PublishedApi
internal fun ConfigurationSection.invalidListElement(key: String, index: Int, element: Any?, expected: String) =
    ConfigurationValueException("Element $index of list at \"${pathOf(key)}\" is not $expected: \"$element\"")

/** Null when [key] is absent (or explicitly null); throws when the value exists but [convert] rejects it. */
@PublishedApi
internal inline fun <T : Any> ConfigurationSection.valueOrNull(key: String, expected: String, convert: (Any) -> T?) =
    get(key)?.let { convert(it) ?: throw invalidValue(key, expected, null) }

/** Throws when [key] is absent or [convert] rejects the value; [message] overrides both default messages. */
@PublishedApi
internal inline fun <T : Any> ConfigurationSection.requireValue(key: String, expected: String, message: String?, convert: (Any) -> T?) =
    convert(get(key) ?: throw missingValue(key, message)) ?: throw invalidValue(key, expected, message)
