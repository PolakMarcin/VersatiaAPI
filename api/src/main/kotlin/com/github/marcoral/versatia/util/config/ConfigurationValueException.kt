package com.github.marcoral.versatia.util.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.MemorySection

/** A required configuration value is missing or has the wrong shape. The message names the full key path. */
class ConfigurationValueException(message: String) : RuntimeException(message)

/** Full path of [key] inside this section, as it appears in the file, joined with the configuration's path separator. */
fun ConfigurationSection.pathOf(key: String): String = MemorySection.createPath(this, key)

@PublishedApi
internal fun ConfigurationSection.missingValue(key: String, message: String?) =
    ConfigurationValueException(message ?: "No value present at \"${pathOf(key)}\"")

@PublishedApi
internal fun ConfigurationSection.invalidValue(key: String, expected: String, message: String?) = ConfigurationValueException(
    message ?: when (val value = get(key)) {
        is ConfigurationSection -> "Value at \"${pathOf(key)}\" is a section, expected $expected"
        else -> "Value at \"${pathOf(key)}\" is not $expected: \"$value\""
    },
)

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

/** Null when [key] is absent; throws when the value is not a list or [convert] rejects an element, naming its index. */
@PublishedApi
internal inline fun <T : Any> ConfigurationSection.listOrNull(key: String, expected: String, convert: (Any) -> T?) =
    valueOrNull(key, LIST_EXPECTED) { it as? List<*> }?.let { convertElements(key, it, expected, convert) }

/** Like [listOrNull], but throws when [key] is absent; [message] overrides the messages for an absent key and a non-list. */
@PublishedApi
internal inline fun <T : Any> ConfigurationSection.requireList(key: String, expected: String, message: String?, convert: (Any) -> T?) =
    convertElements(key, requireValue(key, LIST_EXPECTED, message) { it as? List<*> }, expected, convert)

@PublishedApi
internal inline fun <T : Any> ConfigurationSection.convertElements(key: String, list: List<*>, expected: String, convert: (Any) -> T?) =
    list.mapIndexed { index, element -> element?.let(convert) ?: throw invalidListElement(key, index, element, expected) }

@PublishedApi
internal const val LIST_EXPECTED = "a list"
