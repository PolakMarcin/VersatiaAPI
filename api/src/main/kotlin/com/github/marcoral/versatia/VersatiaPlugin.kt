package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.event.RegisterListeners
import com.github.marcoral.versatia.resources.ExtractResources
import org.bukkit.plugin.java.JavaPlugin

/**
 * Base class of Versatia plugins. There is nothing to override: declare beans and mark functions
 * with `@AutoInvoke` instead. The enhancements declared here apply to every plugin extending the
 * class, as they would to any base class of a main class; see docs/enhancements.md.
 */
@PluginBean
@ExtractResources
@RegisterListeners
abstract class VersatiaPlugin : JavaPlugin() {
    /** Runs this plugin's reload-phase enhancements, as `/versatia reload <name>` does. */
    fun versatiaReload() = VersatiaEngine.instance.reload(this)

    final override fun onEnable() = VersatiaEngine.instance.enhance(this)

    // Dependents are disabled before their dependencies, so the engine is still registered
    final override fun onDisable() = VersatiaEngine.instance.disable(this)
}
