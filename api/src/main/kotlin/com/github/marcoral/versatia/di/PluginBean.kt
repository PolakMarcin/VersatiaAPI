package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.VersatiaEnhancement

/**
 * Makes the plugin's main class a local bean of its own plugin, always named [BeanNames.PLUGIN].
 * Bukkit creates the instance; the runtime only registers it, before any other bean exists. The
 * element also anchors the plugin's bean container in its lifecycle: at a reload it runs the
 * [Reloadable] beans of the plugin. Declared on `VersatiaPlugin`, so every Versatia plugin has it;
 * see docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaEnhancement(BeanProcessors.PLUGIN, enhancementPhase = Phase.PLUGIN_RELOAD_OR_START)
annotation class PluginBean
