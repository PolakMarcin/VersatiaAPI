package com.github.marcoral.versatia

/**
 * Marks an annotation as an enhancement: names the processor that interprets its occurrences and
 * says when. Exactly one of [enhancementPhase] and [enhancementPhaseParamRefName] must be given;
 * `_NULL` and `""` stand for "not given". Repeat it to run several processors for one annotation.
 * See docs/enhancements.md.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
annotation class VersatiaEnhancement(
    /**
     * Fully qualified name of the [EnhancementProcessor] that interprets the occurrences. A name
     * rather than a class, because the processor lives in VersatiaCore or in another plugin, never
     * on the compile classpath of the plugin using the annotation.
     */
    val processorClass: String,
    /** Fixed phase for every occurrence of the annotation. */
    val enhancementPhase: EventType = EventType._NULL,
    /** Name of an `EventType` property of the annotated annotation that holds the phase per occurrence. */
    val enhancementPhaseParamRefName: String = "",
)

/**
 * Marks an annotation whose occurrences are recorded in the descriptor, with their arguments, next
 * to the element they decorate: an enhanced class and its constructor parameters, an enhanced
 * function and its parameters. Processors read them from the element; the runtime never inspects
 * annotations itself. See docs/enhancements.md.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class VersatiaAttribute
