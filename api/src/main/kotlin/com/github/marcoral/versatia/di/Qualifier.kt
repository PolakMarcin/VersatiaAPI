package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.VersatiaAttribute

/**
 * On a bean class: the bean's name instead of the default (its simple class name with a lower-case
 * first letter). On a constructor or `@AutoInvoke` parameter: the name of the bean to inject.
 * See docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@VersatiaAttribute
annotation class Qualifier(val value: String)

/** Among several beans providing a requested type, the one to inject when no `@Qualifier` decides. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaAttribute
annotation class Primary
