package com.github.marcoral.versatia.util.plugin

import java.util.logging.Level
import org.bukkit.plugin.Plugin

/** Logs through the plugin's own logger, so the line carries the plugin's name. */
fun Plugin.log(message: String) = logger.info(message)

fun Plugin.log(level: Level, message: String) = logger.log(level, message)

/** Evaluates [message] only when [level] is enabled. */
inline fun Plugin.log(level: Level = Level.INFO, message: () -> String) {
    if (logger.isLoggable(level)) logger.log(level, message())
}

fun Plugin.logWarning(message: String) = logger.warning(message)

fun Plugin.logSevere(message: String, cause: Throwable? = null) = logger.log(Level.SEVERE, message, cause)
