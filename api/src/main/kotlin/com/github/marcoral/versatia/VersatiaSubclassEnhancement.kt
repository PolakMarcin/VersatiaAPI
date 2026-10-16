package com.github.marcoral.versatia

import kotlin.reflect.KClass

/**
 * Marks an annotation as an enhancement of every class in the plugin's sources that is assignable
 * to [subclassOf]: each such class is recorded as if the annotation had been written on it. The
 * annotation itself is written on the plugin's main class or on one of its base classes, which the
 * descriptor records as the element's origin. Concrete classes with one injectable constructor,
 * objects and the main class itself qualify; abstract classes and interfaces are skipped.
 * Repeat it to enhance several types. See docs/enhancements.md.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
annotation class VersatiaSubclassEnhancement(
    /** Fully qualified name of the [EnhancementProcessor] that interprets the elements; see [VersatiaEnhancement.processorClass]. */
    val processorClass: String,
    /** The type whose subclasses are enhanced; the type itself is skipped unless it is concrete. */
    val subclassOf: KClass<*>,
    val enhancementPhase: Phase,
)
