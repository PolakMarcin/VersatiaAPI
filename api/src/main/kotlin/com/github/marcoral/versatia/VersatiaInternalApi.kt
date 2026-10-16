package com.github.marcoral.versatia

/**
 * Marks the plumbing between the compile-time step, the generated descriptor and VersatiaCore:
 * public only because those live in other modules. Plugin authors and custom enhancement
 * processors never need it; using it anyway warns, and it may change without notice. See
 * docs/api.md.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is internal plumbing of Versatia, meant for its compile-time step and VersatiaCore; it may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
annotation class VersatiaInternalApi
