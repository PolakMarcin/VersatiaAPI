package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.engine.descriptor.Parameter

/**
 * The beans of the plugin being enhanced, for any processor that injects. The DI processors keep
 * it in the plugin state under [KEY]; a processor reads it with `context.state[BeanResolver.KEY]`,
 * which is null when the plugin has no bean container. See docs/enhancements.md.
 */
interface BeanResolver {
    /** Resolves [parameters] for [requester] with the rules of bean constructor dependencies. */
    fun resolve(requester: String, parameters: List<Parameter>): List<Any>

    /** The instance of the bean class [className], or null when the plugin declares no such bean. */
    fun instanceOf(className: String): Any?

    companion object {
        val KEY = StateKey<BeanResolver>("beans")
    }
}
