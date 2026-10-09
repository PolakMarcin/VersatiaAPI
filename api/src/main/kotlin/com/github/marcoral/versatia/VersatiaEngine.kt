package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

/** Implemented by VersatiaCore and published through Bukkit's `ServicesManager`. */
interface VersatiaEngine {
    /** Runs the plugin's start-phase enhancements. */
    fun enhance(plugin: Plugin)

    /** Runs the plugin's stop-phase enhancements and drops everything kept for it. */
    fun disable(plugin: Plugin)
}
