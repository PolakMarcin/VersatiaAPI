package com.github.marcoral.versatia.util.collection

fun <T> MutableSet<T>.addOrThrow(
    element: T,
    exceptionGenerator: () -> Throwable = { IllegalStateException("Element \"$element\" is already in the set") },
) {
    if (!add(element)) throw exceptionGenerator()
}

/**
 * Adds all [elements] or throws when any of them is already in the set or appears more than once
 * in [elements]. The set is left untouched when the call throws.
 */
fun <T> MutableSet<T>.addAllOrThrow(
    elements: Iterable<T>,
    exceptionGenerator: (duplicates: Collection<T>) -> Throwable = ::defaultAddAllException,
) {
    val snapshot = elements.toList()
    val seen = HashSet<T>()
    val duplicates = LinkedHashSet<T>()
    for (element in snapshot) if (element in this || !seen.add(element)) duplicates += element
    if (duplicates.isNotEmpty()) throw exceptionGenerator(duplicates)
    addAll(snapshot)
}

fun <T> MutableSet<T>.addAllOrThrow(
    elements: Array<out T>,
    exceptionGenerator: (duplicates: Collection<T>) -> Throwable = ::defaultAddAllException,
) = addAllOrThrow(elements.asIterable(), exceptionGenerator)

fun <T> MutableSet<T>.addAllOrThrow(
    elements: Sequence<T>,
    exceptionGenerator: (duplicates: Collection<T>) -> Throwable = ::defaultAddAllException,
) = addAllOrThrow(elements.asIterable(), exceptionGenerator)

private fun defaultAddAllException(duplicates: Collection<*>) = IllegalStateException("Duplicate elements: $duplicates")
