package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EventType

/** One recorded occurrence of an enhancement: who interprets it, when, what was annotated and where. */
data class EnhancedElement(
    val processorClass: String,
    val phase: EventType,
    /** The enhancement annotation that produced the element, as written at [origin]. */
    val annotation: AnnotationOccurrence,
    val target: ElementTarget,
    /**
     * The class [annotation] is physically written on: the target's own class for most elements, a
     * base class of the main class for inherited enhancements, and the class carrying a subclass
     * enhancement for the elements it produces.
     */
    val origin: String = target.className,
)

/**
 * An annotation as written at one place. Arguments hold strings, booleans, numbers, chars, enum
 * entry names, class names and lists of those; defaults are included.
 */
data class AnnotationOccurrence(
    val annotationClass: String,
    val arguments: Map<String, Any?> = emptyMap(),
)

/** The occurrence of [annotationClass] among these, if any. */
fun List<AnnotationOccurrence>.occurrenceOf(annotationClass: String) = firstOrNull { it.annotationClass == annotationClass }

sealed interface ElementTarget {
    val className: String
}

/** A constructor or function parameter: its type and the recorded annotations on it. */
data class Parameter(
    val type: String,
    val annotations: List<AnnotationOccurrence> = emptyList(),
)

/**
 * A class whose instances the runtime may create. Type names are Kotlin qualified names
 * (`com.example.Outer.Inner`).
 */
data class ClassTarget(
    override val className: String,
    /** The class itself and every supertype except `Any`. */
    val assignableTypes: List<String>,
    val annotations: List<AnnotationOccurrence>,
    val constructorParameters: List<Parameter>,
    val instantiator: Instantiator,
) : ElementTarget

/** The plugin's main class. Bukkit creates the instance; processors only get to use it. */
data class PluginTarget(
    override val className: String,
    /** The class itself and every supertype except `Any`. */
    val assignableTypes: List<String>,
    val annotations: List<AnnotationOccurrence>,
) : ElementTarget

/** Who holds the instance a [FunctionTarget] is called on. */
enum class FunctionOwner {
    /** The plugin's main class; the instance is the plugin itself. */
    PLUGIN,
    /** A Kotlin `object`. */
    OBJECT,
    /** Any other class; the processor has to obtain the instance itself. */
    INSTANCE,
}

/** A function the runtime may call. [binaryClassName] is the JVM name of the declaring class. */
data class FunctionTarget(
    override val className: String,
    val binaryClassName: String,
    val functionName: String,
    val owner: FunctionOwner,
    val annotations: List<AnnotationOccurrence>,
    val parameters: List<Parameter>,
    val invoker: Invoker,
    /** The attributes written on the declaring class; what applies to a whole class is read from here. */
    val classAnnotations: List<AnnotationOccurrence> = emptyList(),
) : ElementTarget
