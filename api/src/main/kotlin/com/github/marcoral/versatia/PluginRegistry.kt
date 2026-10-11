package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

/**
 * The Versatia plugins running on the server, by the name the runtime registered them under: their
 * `@FriendlyName`, or the `name:` of their `plugin.yml`. Reached through [VersatiaEngine.registry]
 * or injected as a bean, since VersatiaCore exposes it. See docs/enhancements.md.
 */
interface PluginRegistry {
    /** Friendly name to plugin, in the order in which the plugins started. */
    val plugins: Map<String, Plugin>

    /** The names in [plugins]. */
    val friendlyNames: Set<String> get() = plugins.keys

    /** The name [plugin] is registered under, or null when it is not a running Versatia plugin. */
    fun friendlyNameOf(plugin: Plugin): String? = plugins.entries.firstOrNull { it.value === plugin }?.key

    operator fun get(friendlyName: String): Plugin? = plugins[friendlyName]
}
