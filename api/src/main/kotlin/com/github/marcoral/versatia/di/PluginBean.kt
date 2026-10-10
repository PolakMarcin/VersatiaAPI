package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement

/**
 * Makes the plugin's main class a local bean of its own plugin, always named [BeanNames.PLUGIN].
 * Bukkit creates the instance; the runtime only registers it, before any other bean exists.
 * Declared on `VersatiaPlugin`, so every Versatia plugin has it; see docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaEnhancement(BeanProcessors.PLUGIN, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class PluginBean
