package com.github.marcoral.versatia.util.config

import com.github.marcoral.versatia.util.lang.asEnumIgnoreCase
import com.github.marcoral.versatia.util.type.Percent
import java.math.BigInteger
import kotlin.enums.enumEntries
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

// Conventions (see docs/api.md):
//  getXOrNull       - null when absent, throws ConfigurationValueException when present but invalid
//  getX(key, def)   - def when absent, throws when invalid
//  getXOrThrow      - throws when absent or invalid
//  isX              - true only when present and valid, never throws
//  ifXPresent       - runs the action when present, throws when invalid
//  getXList         - empty when absent, throws for a non-list or an invalid element
//  getXListOrNull   - null when absent, otherwise as getXList
//  getXListOrThrow  - throws when absent, otherwise as getXList
// Where Bukkit declares a member of the same name (getInt(key, def), isInt, getIntegerList, ...),
// the lenient member wins over any extension, so only the OrNull and OrThrow forms exist there

/* STRICT PRIMITIVES (Bukkit's getInt & co. silently return 0 for wrong types) */

private const val BOOLEAN_EXPECTED = "a boolean"
private const val INT_EXPECTED = "an int"
private const val LONG_EXPECTED = "a long"
private const val DOUBLE_EXPECTED = "a number"
private const val FLOAT_EXPECTED = "a number within the float range"
private const val STRING_EXPECTED = "a string"

fun ConfigurationSection.getBooleanOrNull(key: String) = valueOrNull(key, BOOLEAN_EXPECTED) { asBoolean(it) }
fun ConfigurationSection.getIntOrNull(key: String) = valueOrNull(key, INT_EXPECTED) { asInt(it) }
fun ConfigurationSection.getLongOrNull(key: String) = valueOrNull(key, LONG_EXPECTED) { asLong(it) }
fun ConfigurationSection.getDoubleOrNull(key: String) = valueOrNull(key, DOUBLE_EXPECTED) { asDouble(it) }
fun ConfigurationSection.getFloatOrNull(key: String) = valueOrNull(key, FLOAT_EXPECTED) { asFloat(it) }
fun ConfigurationSection.getStringOrNull(key: String) = valueOrNull(key, STRING_EXPECTED) { asString(it) }

fun ConfigurationSection.getBooleanOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, BOOLEAN_EXPECTED, exceptionMessage) { asBoolean(it) }
fun ConfigurationSection.getIntOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, INT_EXPECTED, exceptionMessage) { asInt(it) }
fun ConfigurationSection.getLongOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, LONG_EXPECTED, exceptionMessage) { asLong(it) }
fun ConfigurationSection.getDoubleOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, DOUBLE_EXPECTED, exceptionMessage) { asDouble(it) }
fun ConfigurationSection.getFloatOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, FLOAT_EXPECTED, exceptionMessage) { asFloat(it) }
fun ConfigurationSection.getStringOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, STRING_EXPECTED, exceptionMessage) { asString(it) }

inline fun ConfigurationSection.ifBooleanPresent(key: String, action: (Boolean) -> Unit) { getBooleanOrNull(key)?.let(action) }
inline fun ConfigurationSection.ifIntPresent(key: String, action: (Int) -> Unit) { getIntOrNull(key)?.let(action) }
inline fun ConfigurationSection.ifLongPresent(key: String, action: (Long) -> Unit) { getLongOrNull(key)?.let(action) }
inline fun ConfigurationSection.ifDoublePresent(key: String, action: (Double) -> Unit) { getDoubleOrNull(key)?.let(action) }
inline fun ConfigurationSection.ifFloatPresent(key: String, action: (Float) -> Unit) { getFloatOrNull(key)?.let(action) }
inline fun ConfigurationSection.ifStringPresent(key: String, action: (String) -> Unit) { getStringOrNull(key)?.let(action) }

/* STRICT LISTS (Bukkit's getIntegerList & co. skip, wrap or truncate elements and read a single value as an empty list) */

fun ConfigurationSection.getBooleanListOrNull(key: String) = listOrNull(key, BOOLEAN_EXPECTED) { asBoolean(it) }
fun ConfigurationSection.getIntListOrNull(key: String) = listOrNull(key, INT_EXPECTED) { asInt(it) }
fun ConfigurationSection.getLongListOrNull(key: String) = listOrNull(key, LONG_EXPECTED) { asLong(it) }
fun ConfigurationSection.getDoubleListOrNull(key: String) = listOrNull(key, DOUBLE_EXPECTED) { asDouble(it) }
fun ConfigurationSection.getFloatListOrNull(key: String) = listOrNull(key, FLOAT_EXPECTED) { asFloat(it) }
fun ConfigurationSection.getStringListOrNull(key: String) = listOrNull(key, STRING_EXPECTED) { asString(it) }

fun ConfigurationSection.getBooleanListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, BOOLEAN_EXPECTED, exceptionMessage) { asBoolean(it) }
fun ConfigurationSection.getIntListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, INT_EXPECTED, exceptionMessage) { asInt(it) }
fun ConfigurationSection.getLongListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, LONG_EXPECTED, exceptionMessage) { asLong(it) }
fun ConfigurationSection.getDoubleListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, DOUBLE_EXPECTED, exceptionMessage) { asDouble(it) }
fun ConfigurationSection.getFloatListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, FLOAT_EXPECTED, exceptionMessage) { asFloat(it) }
fun ConfigurationSection.getStringListOrThrow(key: String, exceptionMessage: String? = null) = requireList(key, STRING_EXPECTED, exceptionMessage) { asString(it) }

/* UNSIGNED */

private const val UBYTE_EXPECTED = "an integer from 0 to 255"
private const val USHORT_EXPECTED = "an integer from 0 to 65535"
private const val UINT_EXPECTED = "an integer from 0 to 4294967295"
private const val ULONG_EXPECTED = "an integer from 0 to 18446744073709551615"

fun ConfigurationSection.getUIntOrNull(key: String) = valueOrNull(key, UINT_EXPECTED) { asUInt(it) }
fun ConfigurationSection.getUInt(key: String, def: UInt) = getUIntOrNull(key) ?: def
fun ConfigurationSection.getUIntOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, UINT_EXPECTED, exceptionMessage) { asUInt(it) }
fun ConfigurationSection.isUInt(key: String) = get(key)?.let { asUInt(it) } != null
inline fun ConfigurationSection.ifUIntPresent(key: String, action: (UInt) -> Unit) { getUIntOrNull(key)?.let(action) }

fun ConfigurationSection.getULongOrNull(key: String) = valueOrNull(key, ULONG_EXPECTED) { asULong(it) }
fun ConfigurationSection.getULong(key: String, def: ULong) = getULongOrNull(key) ?: def
fun ConfigurationSection.getULongOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, ULONG_EXPECTED, exceptionMessage) { asULong(it) }
fun ConfigurationSection.isULong(key: String) = get(key)?.let { asULong(it) } != null
inline fun ConfigurationSection.ifULongPresent(key: String, action: (ULong) -> Unit) { getULongOrNull(key)?.let(action) }

fun ConfigurationSection.getUByteList(key: String) = listOrNull(key, UBYTE_EXPECTED) { asUnsigned(it, UByte.MAX_VALUE.toLong())?.toUByte() }.orEmpty()
fun ConfigurationSection.getUShortList(key: String) = listOrNull(key, USHORT_EXPECTED) { asUnsigned(it, UShort.MAX_VALUE.toLong())?.toUShort() }.orEmpty()
fun ConfigurationSection.getUIntList(key: String) = listOrNull(key, UINT_EXPECTED) { asUInt(it) }.orEmpty()
fun ConfigurationSection.getULongList(key: String) = listOrNull(key, ULONG_EXPECTED) { asULong(it) }.orEmpty()

/* PERCENT */

private const val PERCENT_EXPECTED = "a percent string such as \"12.5%\" (0-100)"

fun ConfigurationSection.getPercentOrNull(key: String) = valueOrNull(key, PERCENT_EXPECTED) { asPercent(it) }
fun ConfigurationSection.getPercent(key: String, def: Percent) = getPercentOrNull(key) ?: def
fun ConfigurationSection.getPercentOrThrow(key: String, exceptionMessage: String? = null) = requireValue(key, PERCENT_EXPECTED, exceptionMessage) { asPercent(it) }
fun ConfigurationSection.isPercent(key: String) = get(key)?.let { asPercent(it) } != null
inline fun ConfigurationSection.ifPercentPresent(key: String, action: (Percent) -> Unit) { getPercentOrNull(key)?.let(action) }
fun ConfigurationSection.getPercentList(key: String) = listOrNull(key, PERCENT_EXPECTED) { asPercent(it) }.orEmpty()

/* ENUMS (matched by name, ignoring case) */

inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementOrNull(key: String) =
    valueOrNull(key, enumExpectation<T>()) { asEnumIgnoreCase<T>(it.toString()) }
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElement(key: String, def: T) = getEnumElementOrNull<T>(key) ?: def
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementOrThrow(key: String, exceptionMessage: String? = null) =
    requireValue(key, enumExpectation<T>(), exceptionMessage) { asEnumIgnoreCase<T>(it.toString()) }
inline fun <reified T : Enum<T>> ConfigurationSection.isEnumElement(key: String) = get(key)?.let { asEnumIgnoreCase<T>(it.toString()) } != null
inline fun <reified T : Enum<T>> ConfigurationSection.ifEnumElementPresent(key: String, action: (T) -> Unit) { getEnumElementOrNull<T>(key)?.let(action) }
inline fun <reified T : Enum<T>> ConfigurationSection.getEnumElementList(key: String) =
    listOrNull(key, enumExpectation<T>()) { asEnumIgnoreCase<T>(it.toString()) }.orEmpty()

@PublishedApi
internal inline fun <reified T : Enum<T>> enumExpectation() = enumExpectation(enumEntries<T>().map { it.name })

/* ENUMS BY CLASS, for callers that know the enum only at runtime */

fun ConfigurationSection.getEnumElementOrThrow(key: String, enumClass: Class<out Enum<*>>, exceptionMessage: String? = null) =
    requireValue(key, enumExpectation(enumClass), exceptionMessage) { asEnumIgnoreCase(enumClass, it.toString()) }
fun ConfigurationSection.getEnumElementList(key: String, enumClass: Class<out Enum<*>>) =
    listOrNull(key, enumExpectation(enumClass)) { asEnumIgnoreCase(enumClass, it.toString()) }.orEmpty()

private fun enumExpectation(enumClass: Class<out Enum<*>>) = enumExpectation(enumClass.enumConstants.map { it.name })

private const val LISTED_ENUM_CONSTANTS = 20

/** At most [LISTED_ENUM_CONSTANTS] names, so that a typo in a `Material` does not log the whole enum. */
@PublishedApi
internal fun enumExpectation(names: List<String>) =
    "one of ${names.joinToString(limit = LISTED_ENUM_CONSTANTS, truncated = "... (${names.size} in total)")}"

/* BUKKIT OBJECT TYPES */

fun ConfigurationSection.getColorOrThrow(key: String, exceptionMessage: String? = null): Color = requireValue(key, "a Color", exceptionMessage) { getColor(key) }
fun ConfigurationSection.getVectorOrThrow(key: String, exceptionMessage: String? = null): Vector = requireValue(key, "a Vector", exceptionMessage) { getVector(key) }
fun ConfigurationSection.getItemStackOrThrow(key: String, exceptionMessage: String? = null): ItemStack = requireValue(key, "an ItemStack", exceptionMessage) { getItemStack(key) }
fun ConfigurationSection.getLocationOrThrow(key: String, exceptionMessage: String? = null): Location = requireValue(key, "a Location", exceptionMessage) { getLocation(key) }
fun ConfigurationSection.getOfflinePlayerOrThrow(key: String, exceptionMessage: String? = null): OfflinePlayer = requireValue(key, "an OfflinePlayer", exceptionMessage) { getOfflinePlayer(key) }
fun ConfigurationSection.getConfigurationSectionOrThrow(key: String, exceptionMessage: String? = null): ConfigurationSection = requireValue(key, "a section", exceptionMessage) { getConfigurationSection(key) }

inline fun ConfigurationSection.ifColorPresent(key: String, action: (Color) -> Unit) { if (get(key) != null) action(getColorOrThrow(key)) }
inline fun ConfigurationSection.ifVectorPresent(key: String, action: (Vector) -> Unit) { if (get(key) != null) action(getVectorOrThrow(key)) }
inline fun ConfigurationSection.ifItemStackPresent(key: String, action: (ItemStack) -> Unit) { if (get(key) != null) action(getItemStackOrThrow(key)) }
inline fun ConfigurationSection.ifLocationPresent(key: String, action: (Location) -> Unit) { if (get(key) != null) action(getLocationOrThrow(key)) }
inline fun ConfigurationSection.ifOfflinePlayerPresent(key: String, action: (OfflinePlayer) -> Unit) { if (get(key) != null) action(getOfflinePlayerOrThrow(key)) }
inline fun ConfigurationSection.ifSectionPresent(key: String, action: (ConfigurationSection) -> Unit) { if (get(key) != null) action(getConfigurationSectionOrThrow(key)) }

/* GENERIC BUILDING BLOCK FOR CUSTOM TYPES */

/**
 * Throws [ConfigurationValueException] when [key] is absent or when [fetch] returns null for it.
 * [expectedType] is used in the default message, e.g. `"a Material"`.
 */
fun <T : Any> ConfigurationSection.getOrThrow(key: String, expectedType: String, exceptionMessage: String? = null, fetch: (String) -> T?) =
    requireValue(key, expectedType, exceptionMessage) { fetch(key) }

/* STRUCTURE */

/** Direct children as strings; throws when a child is a section or a list. */
fun ConfigurationSection.asStringsMap() = getKeys(false).associateWith { getStringOrThrow(it) }

/** Maps every direct child section; throws when a child is not a section. */
fun <T> ConfigurationSection.mapChildren(mapping: (key: String, section: ConfigurationSection) -> T) =
    getKeys(false).map { mapping(it, getConfigurationSectionOrThrow(it)) }

private fun asBoolean(raw: Any) = raw as? Boolean

private fun asInt(raw: Any) = raw as? Int

private fun asLong(raw: Any) = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> null
}

private fun asDouble(raw: Any) = (raw as? Number)?.toDouble()

/** A finite number that overflows a float is rejected instead of becoming infinity. */
private fun asFloat(raw: Any) = (raw as? Number)?.let { number -> number.toFloat().takeIf { it.isFinite() || !number.toDouble().isFinite() } }

/** Any single value in its text form; sections and lists are rejected instead of being printed. */
private fun asString(raw: Any) = raw.takeUnless { it is ConfigurationSection || it is Map<*, *> || it is Collection<*> }?.toString()

private fun asUnsigned(raw: Any, max: Long) = asLong(raw)?.takeIf { it in 0..max }

private fun asUInt(raw: Any) = asUnsigned(raw, UInt.MAX_VALUE.toLong())?.toUInt()

/** SnakeYAML loads integers above `Long.MAX_VALUE` as [BigInteger]. */
private fun asULong(raw: Any) = when (raw) {
    is BigInteger -> raw.takeIf { it.signum() >= 0 && it.bitLength() <= ULong.SIZE_BITS }?.toLong()?.toULong()
    else -> asLong(raw)?.takeIf { it >= 0 }?.toULong()
}

private fun asPercent(raw: Any) = asString(raw)?.let(Percent::parseOrNull)
