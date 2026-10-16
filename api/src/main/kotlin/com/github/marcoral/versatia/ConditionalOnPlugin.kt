package com.github.marcoral.versatia

/**
 * Keeps the annotated element out of the runtime's work unless every named plugin is enabled on
 * the server: a bean is not created, a listener is not registered, a function is not called, and
 * a value is not read. On a class, an `object` included, it covers the class and every enhanced
 * function and property declared in it, and the class is never loaded, so it may use the optional
 * plugin's classes freely. On a function, only the call is skipped: the declaring class is still
 * loaded and, for a bean, created. List the plugins under `softdepend:` in
 * `plugin.yml`, so that they are enabled first when they are present. Repeatable: written several
 * times, directly or through annotations inheriting it, it requires every plugin named. See
 * docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Repeatable
@VersatiaAttribute
annotation class ConditionalOnPlugin(vararg val value: String)
