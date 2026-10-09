package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement

/**
 * Marks an annotation as a bean scope. Classes annotated with any such scope are analysed at
 * compile time; see docs/enhancements.md.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Bean

/** Injectable only inside the plugin that declares it. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class LocalBean

/** One instance, shared with every plugin loaded afterward. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.EXPOSE_SINGLETON, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class ExposeSingleton

/** A fresh instance for every plugin loaded afterward that depends on it. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.EXPOSE_PROTOTYPE, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class ExposePrototype

/** The VersatiaCore processors of this package, by name: the API does not see the runtime. */
object BeanProcessors {
    const val LOCAL = "com.github.marcoral.versatia.di.LocalBeanEnhancementProcessor"
    const val EXPOSE_SINGLETON = "com.github.marcoral.versatia.di.ExposeSingletonEnhancementProcessor"
    const val EXPOSE_PROTOTYPE = "com.github.marcoral.versatia.di.ExposePrototypeEnhancementProcessor"

    /** Registers the plugin's main class as a bean; the descriptor sends the main class here without any annotation. */
    const val PLUGIN = "com.github.marcoral.versatia.di.PluginBeanEnhancementProcessor"

    /** The scopes declare beans, and beans can only be declared when the plugin starts. */
    val SCOPES = setOf(LOCAL, EXPOSE_SINGLETON, EXPOSE_PROTOTYPE)
}

object BeanNames {
    /** The name of the bean backed by the plugin's main class; reserved, every plugin has it. */
    const val PLUGIN = "plugin"

    /** The default name of a bean: its simple class name with a lower-case first letter. */
    fun defaultFor(simpleClassName: String) = simpleClassName.replaceFirstChar { it.lowercaseChar() }
}
