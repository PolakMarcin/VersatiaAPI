package com.github.marcoral.versatia.event

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.VersatiaInternalApi
import com.github.marcoral.versatia.VersatiaSubclassEnhancement
import org.bukkit.event.Listener

/**
 * Registers every class of the plugin implementing Bukkit's [Listener] when the plugin starts. A
 * listener that is a bean is registered with its bean instance; any other listener is created
 * like a bean, with its constructor parameters injected, but is not put into the container. The
 * main class is registered too when it implements [Listener]. Bukkit unregisters everything when
 * the plugin is disabled. Declared on `VersatiaPlugin`, so every Versatia plugin has it; see
 * docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaSubclassEnhancement(EventProcessors.REGISTER_LISTENERS, Listener::class, Phase.PLUGIN_START_ONLY)
annotation class RegisterListeners

/** The VersatiaCore processors of this package, by name: the API does not see the runtime. */
@VersatiaInternalApi
object EventProcessors {
    const val REGISTER_LISTENERS = "com.github.marcoral.versatia.event.RegisterListenersEnhancementProcessor"
}
