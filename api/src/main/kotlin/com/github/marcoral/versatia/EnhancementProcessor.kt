package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import org.bukkit.plugin.Plugin

/**
 * Interprets the descriptor elements that name it. The runtime loads a processor by the class
 * name given in [VersatiaEnhancement.processorClass] through the enhanced plugin's class loader
 * and keeps one instance per class, created with the no-argument constructor (or the instance of
 * a Kotlin `object`). Anything a processor has to remember about a plugin goes into
 * [EnhancementContext.state] or [EnhancementContext.runtimeState]; see docs/enhancements.md.
 */
interface EnhancementProcessor {
    /** Receives the elements naming this processor whose phase applies to [EnhancementContext.event]. */
    fun process(elements: List<EnhancedElement>, context: EnhancementContext)
}

/** Everything processors know about the plugin being enhanced; lives for one event of one plugin. */
interface EnhancementContext {
    val plugin: Plugin
    val event: LifecycleEvent

    /** State kept for the plugin across events, dropped when it is disabled; shared between processors. */
    val state: StateStore

    /** State kept as long as the runtime runs, for what spans plugins, such as beans exposed between them. */
    val runtimeState: StateStore

    /** Runs [action] once every processor has seen the plugin's elements for this event. */
    fun onPluginEnhanced(action: () -> Unit)
}

/** The loader that defines the plugin's classes, including its generated descriptor. */
val EnhancementContext.classLoader get() = plugin.javaClass.classLoader

class EnhancementException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
