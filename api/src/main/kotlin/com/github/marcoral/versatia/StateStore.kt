package com.github.marcoral.versatia

/** Identifies one entry of a [StateStore]; the type parameter is the entry's type. */
class StateKey<T : Any>(val name: String) {
    override fun toString() = name
}

/**
 * State shared between processors: an entry is created on first use and kept until the store is
 * dropped, when entries implementing [AutoCloseable] are closed, last created first. The context
 * offers two stores, one living as long as the plugin and one as long as the runtime; they are the
 * only way processors share anything, with each other or with their own later invocations.
 * See docs/enhancements.md.
 */
interface StateStore {
    fun <T : Any> getOrPut(key: StateKey<T>, create: () -> T): T

    operator fun <T : Any> get(key: StateKey<T>): T?
}
