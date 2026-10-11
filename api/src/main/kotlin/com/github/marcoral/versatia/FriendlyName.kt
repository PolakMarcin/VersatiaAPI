package com.github.marcoral.versatia

/**
 * The name under which the runtime registers the plugin, used wherever a plugin is addressed by
 * name, such as `/versatia reload <name>`. Without it the plugin is registered under the `name:`
 * of its `plugin.yml`. Belongs on the main class only. See docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaAttribute
annotation class FriendlyName(val value: String)
