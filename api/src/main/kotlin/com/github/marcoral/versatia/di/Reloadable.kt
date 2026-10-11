package com.github.marcoral.versatia.di

/**
 * A bean whose contents derive from something that may change at runtime, such as files in the
 * plugin's data folder. The runtime calls [reload] right after creating the instance and again at
 * every `RELOAD` event of the plugin the instance belongs to: a local bean belongs to its plugin, a
 * prototype instance to the plugin it was made for, an exposed singleton to its owner only.
 * Beans are reloaded in creation order, before any `@AutoInvoke` function of the event runs, so the
 * functions see the new state. Reading in [reload] rather than in the constructor is what makes a
 * bean reloadable; see docs/enhancements.md.
 */
interface Reloadable {
    fun reload()
}
