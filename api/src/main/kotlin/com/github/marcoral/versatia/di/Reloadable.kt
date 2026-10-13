package com.github.marcoral.versatia.di

/**
 * A bean whose contents derive from something that may change at runtime, such as files in the
 * plugin's data folder. The runtime calls [reload] once the plugin's `@Value` properties are set:
 * at the start of the plugin the instance belongs to, after all its beans exist, and again at
 * every `RELOAD` event of that plugin. A local bean belongs to its plugin, a prototype instance to
 * the plugin it was made for, an exposed singleton to its owner only; an instance obtained later,
 * by a function at a reload say, is reloaded as soon as it is obtained. Beans are reloaded in
 * creation order, before any `@AutoInvoke` function of the event, so the functions see the new
 * state, and a `reload()` may read the `@Value` properties of its plugin. The hook exists for the
 * prototypes of modules, which read the consuming plugin's files and have no other way to follow
 * that plugin's reloads; a plugin's own configuration is a `@Value` property instead. See
 * docs/enhancements.md.
 */
interface Reloadable {
    fun reload()
}
