package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.Phase

/** One recorded occurrence of an enhancement: who interprets it, when, what was annotated and where. */
data class EnhancedElement(
    val processorClass: String,
    val phase: Phase,
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
 * An annotation as written at one place. [annotationClass] is the Kotlin qualified name.
 * Arguments hold strings, booleans, numbers, chars, enum entry names, class names (Kotlin
 * qualified names) and lists of those; defaults are included.
 */
data class AnnotationOccurrence(
    val annotationClass: String,
    val arguments: Map<String, Any?> = emptyMap(),
)

/** The occurrence of [annotationClass], a Kotlin qualified name (`Outer.Inner`, not `Outer$Inner`), among these, if any. */
fun List<AnnotationOccurrence>.occurrenceOf(annotationClass: String) = firstOrNull { it.annotationClass == annotationClass }

/** The occurrence of [A] among these, if any; matches the recorded name of a nested annotation too. */
inline fun <reified A : Annotation> List<AnnotationOccurrence>.occurrenceOf() = occurrenceOf(A::class.qualifiedName!!)

sealed interface ElementTarget {
    val className: String

    /** The recorded annotations written on the element itself. */
    val annotations: List<AnnotationOccurrence>
}

/** A function or property of a class: what a processor needs to find the instance it belongs to. */
sealed interface MemberTarget : ElementTarget {
    /** The JVM name of the declaring class. */
    val binaryClassName: String
    val owner: MemberOwner

    /** The attributes written on the declaring class; what applies to a whole class is read from here. */
    val classAnnotations: List<AnnotationOccurrence>

    /** How messages name the member: `com.example.Shop.open()` or `com.example.Shop.tax`. */
    val description: String
}

/**
 * A constructor or function parameter: its type and the recorded annotations on it. An [optional]
 * constructor parameter has a default value, which it keeps when no bean provides its type. A
 * parameter with [suppliedBy] is not injected: that processor supplies its argument, as the
 * `@Value` processor does for a constructor property.
 */
data class Parameter(
    val type: String,
    val annotations: List<AnnotationOccurrence> = emptyList(),
    val optional: Boolean = false,
    val suppliedBy: String? = null,
)

/** A type as written on a property: a Kotlin qualified name, its type arguments and whether it accepts `null`. */
data class TypeReference(
    val name: String,
    val arguments: List<TypeReference> = emptyList(),
    val nullable: Boolean = false,
) {
    override fun toString() = name + (if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")) + (if (nullable) "?" else "")
}

/**
 * A class whose instances the runtime may create. Type names are Kotlin qualified names
 * (`com.example.Outer.Inner`).
 */
data class ClassTarget(
    override val className: String,
    /** The class itself and every supertype except `Any`. */
    val assignableTypes: List<String>,
    override val annotations: List<AnnotationOccurrence>,
    val constructorParameters: List<Parameter>,
    val instantiator: Instantiator,
) : ElementTarget

/** The plugin's main class. Bukkit creates the instance; processors only get to use it. */
data class PluginTarget(
    override val className: String,
    /** The class itself and every supertype except `Any`. */
    val assignableTypes: List<String>,
    override val annotations: List<AnnotationOccurrence>,
) : ElementTarget

/** Who holds the instance a [FunctionTarget] is called on or a [PropertyTarget] is set on. */
enum class MemberOwner {
    /** The plugin's main class; the instance is the plugin itself. */
    PLUGIN,
    /** A Kotlin `object`. */
    OBJECT,
    /** Any other class; the processor has to obtain the instance itself. */
    INSTANCE,
}

/** A function the runtime may call. */
data class FunctionTarget(
    override val className: String,
    override val binaryClassName: String,
    val functionName: String,
    override val owner: MemberOwner,
    override val annotations: List<AnnotationOccurrence>,
    val parameters: List<Parameter>,
    val invoker: Invoker,
    override val classAnnotations: List<AnnotationOccurrence> = emptyList(),
) : MemberTarget {
    override val description get() = "$className.$functionName()"
}

/** A mutable property the runtime may set. */
data class PropertyTarget(
    override val className: String,
    override val binaryClassName: String,
    val propertyName: String,
    override val owner: MemberOwner,
    val type: TypeReference,
    override val annotations: List<AnnotationOccurrence>,
    val setter: Setter,
    override val classAnnotations: List<AnnotationOccurrence> = emptyList(),
    /**
     * For a property declared in the primary constructor of a bean, the index of that constructor
     * parameter: its value is passed to the constructor when the bean is created.
     */
    val constructorParameter: Int? = null,
) : MemberTarget {
    override val description get() = "$className.$propertyName"
}
