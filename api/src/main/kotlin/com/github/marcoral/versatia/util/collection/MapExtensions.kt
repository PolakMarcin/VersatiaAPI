package com.github.marcoral.versatia.util.collection

/** A key that would be written twice: once with [existingValue], once with [newValue]. */
data class MapConflict<K, V>(val key: K, val existingValue: V, val newValue: V)

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V>.getOrThrow(
    key: K,
    exceptionGenerator: () -> Throwable = { NoSuchElementException("No value assigned to key \"$key\"") },
) = if (containsKey(key)) get(key) as V else throw exceptionGenerator()

@Suppress("UNCHECKED_CAST")
fun <K, V> MutableMap<K, V>.putOrThrow(
    key: K,
    value: V,
    exceptionGenerator: (existingValue: V) -> Throwable = { IllegalStateException("Key \"$key\" already has a value ($it)") },
) {
    if (containsKey(key)) throw exceptionGenerator(get(key) as V)
    put(key, value)
}

/**
 * Puts all [entries] or throws when a key is already present in the map or appears more than once
 * in [entries]. The map is left untouched when the call throws.
 */
@Suppress("UNCHECKED_CAST")
fun <K, V> MutableMap<K, V>.putAllOrThrow(
    entries: Iterable<Pair<K, V>>,
    exceptionGenerator: (conflicts: List<MapConflict<K, V>>) -> Throwable = ::defaultConflictException,
) {
    val staged = LinkedHashMap<K, V>()
    val conflicts = mutableListOf<MapConflict<K, V>>()
    for ((key, value) in entries) when {
        containsKey(key) -> conflicts += MapConflict(key, get(key) as V, value)
        staged.containsKey(key) -> conflicts += MapConflict(key, staged[key] as V, value)
        else -> staged[key] = value
    }
    if (conflicts.isNotEmpty()) throw exceptionGenerator(conflicts)
    putAll(staged)
}

fun <K, V> MutableMap<K, V>.putAllOrThrow(
    entries: Map<out K, V>,
    exceptionGenerator: (conflicts: List<MapConflict<K, V>>) -> Throwable = ::defaultConflictException,
) = putAllOrThrow(entries.entries.map { it.toPair() }, exceptionGenerator)

fun <K, V> MutableMap<K, V>.putAllOrThrow(
    entries: Array<out Pair<K, V>>,
    exceptionGenerator: (conflicts: List<MapConflict<K, V>>) -> Throwable = ::defaultConflictException,
) = putAllOrThrow(entries.asIterable(), exceptionGenerator)

fun <K, V> MutableMap<K, V>.putAllOrThrow(
    entries: Sequence<Pair<K, V>>,
    exceptionGenerator: (conflicts: List<MapConflict<K, V>>) -> Throwable = ::defaultConflictException,
) = putAllOrThrow(entries.asIterable(), exceptionGenerator)

/** Merges all maps into one or throws when the same key occurs in more than one of them. */
@Suppress("UNCHECKED_CAST")
fun <K, V> Iterable<Map<K, V>>.mergeOrThrow(
    exceptionGenerator: (conflicts: List<MapConflict<K, V>>) -> Throwable = ::defaultConflictException,
): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    val conflicts = mutableListOf<MapConflict<K, V>>()
    for (map in this) for ((key, value) in map) {
        if (result.containsKey(key)) conflicts += MapConflict(key, result[key] as V, value) else result[key] = value
    }
    if (conflicts.isNotEmpty()) throw exceptionGenerator(conflicts)
    return result
}

private fun defaultConflictException(conflicts: List<MapConflict<*, *>>) = IllegalStateException(
    "Duplicate keys: " + conflicts.joinToString { "[key: \"${it.key}\", existing: \"${it.existingValue}\", new: \"${it.newValue}\"]" },
)
