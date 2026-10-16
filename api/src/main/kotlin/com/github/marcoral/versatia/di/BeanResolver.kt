package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.VersatiaInternalApi
import com.github.marcoral.versatia.engine.descriptor.Parameter

/**
 * The beans of the plugin being enhanced, for any processor that injects. The DI processors keep
 * it in the plugin state under [KEY]; a processor reads it with `context.state[BeanResolver.KEY]`,
 * which is null when the plugin has no bean container. See docs/enhancements.md.
 */
interface BeanResolver {
    /**
     * Resolves [parameters] for [requester] with the rules of bean constructor dependencies; an
     * optional parameter no bean provides resolves to `DefaultArgument`.
     */
    fun resolve(requester: String, parameters: List<Parameter>): List<Any>

    /**
     * Supplies the argument for the constructor parameter at [index] of the bean class [className],
     * a parameter whose `Parameter.suppliedBy` names the calling processor; only before the beans
     * are created, so at start in `process` itself. [value] is called for every instance the
     * constructor makes: the plugin's own and, for an exposed prototype, each one made for another
     * plugin, so each instance gets the argument current at its creation and none shares a cell.
     */
    @VersatiaInternalApi
    fun supply(className: String, index: Int, value: () -> Any?)

    /**
     * Runs [action] on every instance of the bean class [className] right after its constructor,
     * before it is injected anywhere: at once on the instances that exist, then on each one made
     * later, such as a prototype instance made for another plugin. False, with nothing registered,
     * when the plugin declares no such bean.
     */
    @VersatiaInternalApi
    fun onInstanceCreated(className: String, action: (instance: Any) -> Unit): Boolean

    /** The instance of the bean class [className], or null when the plugin declares no such bean. */
    fun instanceOf(className: String): Any?

    /**
     * Every live instance of the bean class [className], without creating any: the plugin's own
     * and, for an exposed prototype, those made for plugins that are still running.
     */
    @VersatiaInternalApi
    fun instancesOf(className: String): List<Any>

    companion object {
        val KEY = StateKey<BeanResolver>("beans")
    }
}
