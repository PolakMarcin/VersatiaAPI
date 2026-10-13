package com.github.marcoral.versatia.util.config

import com.github.marcoral.versatia.util.lang.asEnumIgnoreCase
import com.github.marcoral.versatia.util.type.Percent
import kotlin.enums.enumEntries
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

// Conventions (see docs/api.md):
//  getXOrNull   - null when absent, throws ConfigurationValueException when present but invalid
//  getX(key, def) - def when absent, throws when invalid
//  getXOrThrow  - throws when absent or invalid
//  isX          - true only when present and valid, never throws
//  ifXPresent   - runs the action when present, throws when invalid

/* STRICT PRIMITIVES (Bukkit's getInt & co. silently return 0 for wrong types) */

fun ConfigurationSection.getBooleanOrNull(key: String) = valueOrNull(key, "a boolean") { it as? Boolean }
fun ConfigurationSection.getIntOrNull(key: String) = valueOrNull(key, "an int") { it as? Int }
fun ConfigurationSection.getLongOrNull(key: String) = valueOrNull(key, "a long") { asLong(it) }
fun ConfigurationSection.getDoubleOrNull(key: String) = valueOrNull(key, "a number") { (it as? Number)?.toDouble() }
fun ConfigurationSection.getFloatOrNull(key: String) = valueOrNull(key, "a number") { (it as? Number)?.toFloat() }

fun ConfigurationSection.getBooleanOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a boolean", exceptionMessage) { it as? Boolean }
fun ConfigurationSection.getIntOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "an int", exceptionMessage) { it as? Int }
fun ConfigurationSection.getLongOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a long", exceptionMessage) { asLong(it) }
fun ConfigurationSection.getDoubleOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a number", exceptionMessage) { (it as? Number)?.toDouble() }
fun ConfigurationSection.getFloatOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a number", exceptionMessage) { (it as? Number)?.toFloat() }

fun ConfigurationSection.ifBooleanPresent(key: String, action: (Boolean) -> Unit) { getBooleanOrNull(key)?.let(action) }
fun ConfigurationSection.ifIntPresent(key: String, action: (Int) -> Unit) { getIntOrNull(key)?.let(action) }
fun ConfigurationSection.ifLongPresent(key: String, action: (Long) -> Unit) { getLongOrNull(key)?.let(action) }
fun ConfigurationSection.ifDoublePresent(key: String, action: (Double) -> Unit) { getDoubleOrNull(key)?.let(action) }
fun ConfigurationSection.ifFloatPresent(key: String, action: (Float) -> Unit) { getFloatOrNull(key)?.let(action) }

/* UNSIGNED */

fun ConfigurationSection.getUIntOrNull(key: String) = valueOrNull(key, "a non-negative int") { asUInt(it) }
fun ConfigurationSection.getUInt(key: String, def: UInt) = getUIntOrNull(key) ?: def
fun ConfigurationSection.getUIntOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a non-negative int", exceptionMessage) { asUInt(it) }
fun ConfigurationSection.isUInt(key: String) = get(key)?.let { asUInt(it) } != null
fun ConfigurationSection.ifUIntPresent(key: String, action: (UInt) -> Unit) { getUIntOrNull(key)?.let(action) }

fun ConfigurationSection.getULongOrNull(key: String) = valueOrNull(key, "a non-negative long") { asULong(it) }
fun ConfigurationSection.getULong(key: String, def: ULong) = getULongOrNull(key) ?: def
fun ConfigurationSection.getULongOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, "a non-negative long", exceptionMessage) { asULong(it) }
fun ConfigurationSection.isULong(key: String) = get(key)?.let { asULong(it) } != null
fun ConfigurationSection.ifULongPresent(key: String, action: (ULong) -> Unit) { getULongOrNull(key)?.let(action) }

fun ConfigurationSection.getUByteList(key: String) =
    getByteList(key).mapIndexed { i, v -> v.takeIf { it >= 0 }?.toUByte() ?: throw invalidListElement(key, i, v, "a non-negative byte") }
fun ConfigurationSection.getUShortList(key: String) =
    getShortList(key).mapIndexed { i, v -> v.takeIf { it >= 0 }?.toUShort() ?: throw invalidListElement(key, i, v, "a non-negative short") }
fun ConfigurationSection.getUIntList(key: String) =
    getIntegerList(key).mapIndexed { i, v -> v.takeIf { it >= 0 }?.toUInt() ?: throw invalidListElement(key, i, v, "a non-negative int") }
fun ConfigurationSection.getULongList(key: String) =
    getLongList(key).mapIndexed { i, v -> v.takeIf { it >= 0 }?.toULong() ?: throw invalidListElement(key, i, v, "a non-negative long") }

/* PERCENT */

private const val PERCENT_EXPECTED = "a percent string such as \"12.5%\" (0-100)"

fun ConfigurationSection.getPercentOrNull(key: String) = valueOrNull(key, PERCENT_EXPECTED) { Percent.parseOrNull(it.toString()) }
fun ConfigurationSection.getPercent(key: String, def: Percent) = getPercentOrNull(key) ?: def
fun ConfigurationSection.getPercentOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, PERCENT_EXPECTED, exceptionMessage) { Percent.parseOrNull(it.toString()) }
fun ConfigurationSection.isPercent(key: String) = get(key)?.let { Percent.parseOrNull(it.toString()) } != null
fun ConfigurationSection.ifPercentPresent(key: String, action: (Percent) -> Unit) { getPercentOrNull(key)?.let(action) }
fun ConfigurationSection.getPercentList(key: String) =
    getStringList(key).mapIndexed { i, v -> Percent.parseOrNull(v) ?: throw invalidListElement(key, i, v, PERCENT_EXPECTED) }

/* ENUMS (matched by name, ignoring case) */

inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementOrNull(key: String) =
    valueOrNull(key, enumExpectation<T>()) { asEnumIgnoreCase<T>(it.toString()) }
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElement(key: String, def: T) = getEnumElementOrNull<T>(key) ?: def
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementOrThrow(key: String, exceptionMessage: String? = null) =
    requireValue(key, enumExpectation<T>(), exceptionMessage) { asEnumIgnoreCase<T>(it.toString()) }
inline fun <reified T : Enum<T>> ConfigurationSection.isEnumElement(key: String) = get(key)?.let { asEnumIgnoreCase<T>(it.toString()) } != null
inline fun <reified T : Enum<T>> ConfigurationSection.ifEnumElementPresent(key: String, action: (T) -> Unit) { getEnumElementOrNull<T>(key)?.let(action) }
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementList(key: String) =
    getStringList(key).mapIndexed { i, v -> asEnumIgnoreCase<T>(v) ?: throw invalidListElement(key, i, v, enumExpectation<T>()) }

@PublishedApi
internal inline fun <reified T : Enum<T>> enumExpectation() = "one of ${enumEntries<T>().joinToString { it.name }}"

/* ENUMS BY CLASS, for callers that know the enum only at runtime */

fun ConfigurationSection.getEnumElementOrThrow(key: String, enumClass: Class<out Enum<*>>, exceptionMessage: String? = null) =
    requireValue(key, enumExpectation(enumClass), exceptionMessage) { asEnumIgnoreCase(enumClass, it.toString()) }
fun ConfigurationSection.getEnumElementList(key: String, enumClass: Class<out Enum<*>>) =
    getStringList(key).mapIndexed { i, v -> asEnumIgnoreCase(enumClass, v) ?: throw invalidListElement(key, i, v, enumExpectation(enumClass)) }

@PublishedApi
internal fun enumExpectation(enumClass: Class<out Enum<*>>) = "one of ${enumClass.enumConstants.joinToString { it.name }}"

/* BUKKIT OBJECT TYPES */

fun ConfigurationSection.getStringOrThrow(key: String, exceptionMessage: String? = null): String = requireValue(key, "a string", exceptionMessage) { getString(key) }
fun ConfigurationSection.getColorOrThrow(key: String, exceptionMessage: String? = null): Color = requireValue(key, "a Color", exceptionMessage) { getColor(key) }
fun ConfigurationSection.getVectorOrThrow(key: String, exceptionMessage: String? = null): Vector = requireValue(key, "a Vector", exceptionMessage) { getVector(key) }
fun ConfigurationSection.getItemStackOrThrow(key: String, exceptionMessage: String? = null): ItemStack = requireValue(key, "an ItemStack", exceptionMessage) { getItemStack(key) }
fun ConfigurationSection.getLocationOrThrow(key: String, exceptionMessage: String? = null): Location = requireValue(key, "a Location", exceptionMessage) { getLocation(key) }
fun ConfigurationSection.getOfflinePlayerOrThrow(key: String, exceptionMessage: String? = null): OfflinePlayer = requireValue(key, "an OfflinePlayer", exceptionMessage) { getOfflinePlayer(key) }
fun ConfigurationSection.getConfigurationSectionOrThrow(key: String, exceptionMessage: String? = null): ConfigurationSection = requireValue(key, "a section", exceptionMessage) { getConfigurationSection(key) }

fun ConfigurationSection.ifStringPresent(key: String, action: (String) -> Unit) { if (get(key) != null) action(getStringOrThrow(key)) }
fun ConfigurationSection.ifColorPresent(key: String, action: (Color) -> Unit) { if (get(key) != null) action(getColorOrThrow(key)) }
fun ConfigurationSection.ifVectorPresent(key: String, action: (Vector) -> Unit) { if (get(key) != null) action(getVectorOrThrow(key)) }
fun ConfigurationSection.ifItemStackPresent(key: String, action: (ItemStack) -> Unit) { if (get(key) != null) action(getItemStackOrThrow(key)) }
fun ConfigurationSection.ifLocationPresent(key: String, action: (Location) -> Unit) { if (get(key) != null) action(getLocationOrThrow(key)) }
fun ConfigurationSection.ifOfflinePlayerPresent(key: String, action: (OfflinePlayer) -> Unit) { if (get(key) != null) action(getOfflinePlayerOrThrow(key)) }
fun ConfigurationSection.ifSectionPresent(key: String, action: (ConfigurationSection) -> Unit) { if (get(key) != null) action(getConfigurationSectionOrThrow(key)) }

/* GENERIC BUILDING BLOCK FOR CUSTOM TYPES */

/**
 * Throws [ConfigurationValueException] when [key] is absent or when [fetch] returns null for it.
 * [expectedType] is used in the default message, e.g. `"a Material"`.
 */
fun <T : Any> ConfigurationSection.getOrThrow(key: String, expectedType: String, exceptionMessage: String? = null, fetch: (String) -> T?) =
    requireValue(key, expectedType, exceptionMessage) { fetch(key) }

/* STRUCTURE */

/** Direct children as strings; throws when a child is a section. */
fun ConfigurationSection.asStringsMap(): Map<String, String> = getValues(false).mapValues { (key, value) ->
    if (value is ConfigurationSection) throw ConfigurationValueException("Value at \"${pathOf(key)}\" is a section, expected a scalar")
    value.toString()
}

/** Maps every direct child section; throws when a child is not a section. */
fun <T> ConfigurationSection.mapChildren(mapping: (key: String, section: ConfigurationSection) -> T) =
    getKeys(false).map { mapping(it, getConfigurationSectionOrThrow(it)) }

private fun asLong(raw: Any) = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> null
}

private fun asUInt(raw: Any) = (raw as? Int)?.takeIf { it >= 0 }?.toUInt()

private fun asULong(raw: Any) = asLong(raw)?.takeIf { it >= 0 }?.toULong()
