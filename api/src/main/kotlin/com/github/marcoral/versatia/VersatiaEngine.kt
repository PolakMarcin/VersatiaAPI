package com.github.marcoral.versatia

import com.github.marcoral.versatia.util.server.loadServiceOrNull
import org.bukkit.plugin.Plugin

/** Implemented by VersatiaCore and published through Bukkit's `ServicesManager`; see [instance]. */
interface VersatiaEngine {
    /** The plugins this engine has enhanced, by friendly name. */
    val registry: PluginRegistry

    /** Runs the plugin's start-phase enhancements. */
    fun enhance(plugin: Plugin)

    /** Runs the plugin's reload-phase enhancements; the state kept for it stays. */
    fun reload(plugin: Plugin)

    /** Runs the plugin's stop-phase enhancements and drops everything kept for it. */
    fun disable(plugin: Plugin)

    companion object {
        /** The running engine; `VersatiaEngine.getInstance()` from Java. Fails when VersatiaCore is not enabled. */
        @JvmStatic
        val instance: VersatiaEngine
            get() = loadServiceOrNull<VersatiaEngine>() ?: error("VersatiaCore is not running. Add it to `depend:` in plugin.yml.")
    }
}
