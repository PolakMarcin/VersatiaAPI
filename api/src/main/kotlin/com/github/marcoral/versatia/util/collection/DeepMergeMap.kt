package com.github.marcoral.versatia.util.collection

/** What [DeepMergeMap] does when a key exists on both sides and the values cannot be merged structurally. */
enum class MergeConflictStrategy {
    /** The incoming value replaces the existing one. */
    OVERRIDE,

    /** The existing value stays. */
    KEEP_EXISTING,

    /** Both values end up in a list; collections are flattened into it. */
    COMBINE_INTO_LIST,

    /** An [IllegalStateException] naming the key path is thrown. */
    THROW,
}

/**
 * A mutable map that merges other maps into itself recursively: nested maps are merged key by key,
 * nested collections are concatenated, and every other clash is resolved by [conflictStrategy].
 * See docs/api.md for the exact rules.
 */
class DeepMergeMap<K> private constructor(
    private val backing: LinkedHashMap<K, Any?>,
    val conflictStrategy: MergeConflictStrategy,
) : MutableMap<K, Any?> by backing {

    /** Starts with a deep copy of [initial], as if it had been merged into an empty map. */
    constructor(
        initial: Map<out K, Any?> = emptyMap(),
        conflictStrategy: MergeConflictStrategy = MergeConflictStrategy.OVERRIDE,
    ) : this(LinkedHashMap(), conflictStrategy) {
        for ((key, value) in initial) backing[key] = deepCopy(value)
    }

    /** Merges [other] into this map. [other] and its nested values are never mutated. */
    fun merge(other: Map<out K, Any?>) = mergeInto(backing, other, emptyList())

    operator fun plusAssign(other: Map<out K, Any?>) = merge(other)

    /** Returns a new map holding the result of merging [other] into a deep copy of this map. */
    fun mergedWith(other: Map<out K, Any?>) = DeepMergeMap(this, conflictStrategy).apply { merge(other) }

    override fun equals(other: Any?) = backing == other
    override fun hashCode() = backing.hashCode()
    override fun toString() = backing.toString()

    private fun <T> mergeInto(destination: MutableMap<T, Any?>, source: Map<out T, Any?>, path: List<Any?>) {
        for ((key, newValue) in source) {
            val existing = destination[key]
            destination[key] = when {
                !destination.containsKey(key) -> deepCopy(newValue)
                existing is Map<*, *> && newValue is Map<*, *> -> mergeNested(existing, newValue, path + key)
                existing is Collection<*> && newValue is Collection<*> -> existing.toList() + newValue.map(::deepCopy)
                else -> resolveConflict(existing, newValue, path + key)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeNested(existing: Map<*, *>, newValue: Map<*, *>, path: List<Any?>) =
        DeepMergeMap(existing as Map<Any?, Any?>, conflictStrategy).apply { mergeInto(backing, newValue as Map<Any?, Any?>, path) }

    private fun resolveConflict(existing: Any?, newValue: Any?, path: List<Any?>) = when (conflictStrategy) {
        MergeConflictStrategy.OVERRIDE -> deepCopy(newValue)
        MergeConflictStrategy.KEEP_EXISTING -> existing
        MergeConflictStrategy.COMBINE_INTO_LIST -> when {
            existing is Collection<*> -> existing.toList() + deepCopy(newValue)
            newValue is Collection<*> -> listOf(existing) + newValue.map(::deepCopy)
            else -> listOf(existing, deepCopy(newValue))
        }
        MergeConflictStrategy.THROW -> throw IllegalStateException(
            "Conflicting values at \"${path.joinToString(".")}\": existing \"$existing\", new \"$newValue\"",
        )
    }

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> DeepMergeMap<Any?>(conflictStrategy = conflictStrategy).also { copy ->
            for ((key, nested) in value) copy.backing[key] = deepCopy(nested)
        }
        is Collection<*> -> value.map(::deepCopy)
        else -> value
    }
}
