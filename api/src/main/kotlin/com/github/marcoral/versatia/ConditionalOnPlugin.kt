package com.github.marcoral.versatia

/**
 * Keeps the annotated element out of the runtime's work unless every named plugin is enabled on
 * the server: a bean is not created, a listener is not registered, a function is not called, and
 * the class behind the element is never loaded. On a class, an `object` included, it covers the
 * class and every enhanced function declared in it. List the plugins under `softdepend:` in
 * `plugin.yml`, so that they are enabled first when they are present. See docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@VersatiaAttribute
annotation class ConditionalOnPlugin(vararg val value: String)
