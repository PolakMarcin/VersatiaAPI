package com.github.marcoral.versatia.config

/**
 * Holds a `@Value` together with the value it replaced, for code that reacts to a change at a
 * reload: `@Value("interval") val interval = PreviousValueTracking<Int>()`. The runtime sets
 * [value] at start and at every reload; the property itself stays a `val`. [previous] is the
 * value the last assignment replaced and [changed] says whether it differs. The first assignment
 * counts as a change, so a `PLUGIN_RELOAD_OR_START` function that checks `changed` acts at start
 * and at the reloads that matter. Values are compared with `equals`: scalars, enums, lists, sets,
 * maps and data classes compare by content, but a `ConfigurationSection` or a mapper result of a
 * class without `equals` is a new object at every reload and always counts as changed, so map
 * such values to a data class. See docs/enhancements.md.
 */
class PreviousValueTracking<T> {
    private var assignments = 0
    private var current: T? = null
    private var before: T? = null

    /** The value last set; reading it before the runtime set one is an error. */
    var value: T
        @Suppress("UNCHECKED_CAST")
        get() = if (assignments == 0) throw IllegalStateException("No value has been set yet") else current as T
        set(value) {
            before = current
            current = value
            assignments++
        }

    /** The value [value] replaced, or null when nothing has been replaced yet; see [hasPrevious]. */
    val previous get() = if (hasPrevious) before else null

    /** Whether [value] replaced an earlier one; false until the second assignment. */
    val hasPrevious get() = assignments >= 2

    /** Whether the last assignment changed the value; true for the first one, false before any. */
    val changed get() = assignments == 1 || hasPrevious && current != before

    override fun toString() = if (assignments == 0) "PreviousValueTracking(unset)" else "PreviousValueTracking($current, previous=${if (hasPrevious) before else "none"})"
}
