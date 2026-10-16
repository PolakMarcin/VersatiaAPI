package com.github.marcoral.versatia

import org.bukkit.Bukkit
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

    /**
     * Also the static entry point to server-wide work, declared in VersatiaEngineExtensions.kt:
     * `VersatiaEngine.broadcast(message)`, `VersatiaEngine.loadService<Economy>()`, `VersatiaEngine.reload("shop")`.
     * Work on an object at hand goes through that object: `plugin.runTaskLater(20) { }`, `plugin.logger`,
     * `event.callEvent()`. [enhance] and [disable] belong to `VersatiaPlugin` alone and have no shortcut.
     */
    companion object {
        /** The running engine; `VersatiaEngine.getInstance()` from Java. Fails when VersatiaCore is not enabled, naming the likely cause. */
        @JvmStatic
        val instance: VersatiaEngine
            get() = loadServiceOrNull<VersatiaEngine>() ?: error(notRunningMessage())

        /** Core registers the engine under its own copy of this interface, so a second copy of the API sees no service. */
        private fun notRunningMessage() =
            if (Bukkit.getServicesManager().knownServices.any { it.name == VersatiaEngine::class.java.name }) {
                "VersatiaCore is running, but this plugin carries its own copy of VersatiaAPI. Do not bundle VersatiaAPI into the plugin JAR " +
                    "(no shadow or fat-JAR step); VersatiaCore provides it at runtime."
            } else {
                "VersatiaCore is not running. Check that its JAR is in plugins/, that it enabled without errors (see the log above), " +
                    "that plugin.yml lists it under depend: and that the plugin does not set load: STARTUP."
            }
    }
}
