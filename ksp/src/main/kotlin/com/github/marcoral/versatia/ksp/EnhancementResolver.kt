package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.VersatiaSubclassEnhancement
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.SubclassEnhancement
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Walks the annotations an annotation *inherits* (its meta-annotations, transitively) and resolves
 * every `@VersatiaEnhancement` found on the way, including the phase it runs at.
 * See docs/enhancements.md.
 */
internal class EnhancementResolver(private val logger: KSPLogger) {
    var hadErrors = false
        private set

    private val enhancementName = VersatiaEnhancement::class.qualifiedName!!
    private val containerName = "$enhancementName.Container"
    private val subclassName = VersatiaSubclassEnhancement::class.qualifiedName!!
    private val subclassContainerName = "$subclassName.Container"
    private val eventTypeName = EventType::class.qualifiedName!!

    /** A visited annotation class together with the occurrence through which it was reached. */
    class Link(val declaration: KSClassDeclaration, val occurrence: KSAnnotation)

    /** [annotation] itself first, then everything it inherits, breadth first. */
    fun chainOf(annotation: KSAnnotation): List<Link> {
        val start = annotation.declaration() ?: return emptyList()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<Link>()
        val queue = ArrayDeque(listOf(Link(start, annotation)))
        while (queue.isNotEmpty()) {
            val link = queue.removeFirst()
            val name = link.declaration.qualifiedName?.asString() ?: continue
            if (!visited.add(name)) continue
            result += link
            queue += link.declaration.annotations.mapNotNull { meta -> meta.declaration()?.let { Link(it, meta) } }
        }
        return result
    }

    fun inherits(chain: List<Link>, annotationName: String) = chain.drop(1).any { it.declaration.qualifiedName?.asString() == annotationName }

    /**
     * Enhancements declared along [chain]. [usage] is where errors are reported when the carrier
     * annotation comes from the classpath and has no source location of its own.
     */
    fun enhancementsOf(chain: List<Link>, usage: KSNode) = chain.flatMap { link ->
        link.declaration.annotations
            .flatMap { unwrapRepeatable(it, containerName) }
            .filter { it.declaration()?.qualifiedName?.asString() == enhancementName }
            .mapNotNull { resolve(it, link, usage) }
    }

    /** Subclass enhancements declared along [chain]; errors are reported like in [enhancementsOf]. */
    fun subclassEnhancementsOf(chain: List<Link>, usage: KSNode) = chain.flatMap { link ->
        link.declaration.annotations
            .flatMap { unwrapRepeatable(it, subclassContainerName) }
            .filter { it.declaration()?.qualifiedName?.asString() == subclassName }
            .mapNotNull { resolveSubclass(it, link, usage) }
    }

    private fun resolveSubclass(enhancement: KSAnnotation, carrier: Link, usage: KSNode): SubclassEnhancement? {
        val carrierName = carrier.declaration.simpleName.asString()
        val reportAt = if (carrier.declaration.origin == Origin.KOTLIN) carrier.declaration else usage
        val processorClass = (enhancement.argument("processorClass") as? String)?.takeIf { it.isNotBlank() }
            ?: return fail(reportAt, "@VersatiaSubclassEnhancement on $carrierName has no processorClass")
        val subclassOf = (enhancement.argument("subclassOf") as? KSType)?.declaration as? KSClassDeclaration
            ?: return fail(reportAt, "@VersatiaSubclassEnhancement(\"$processorClass\") on $carrierName names no class in subclassOf")
        val phase = enhancement.argument("enhancementPhase").asEventType()
        if (phase == EventType._NULL) return fail(reportAt, "@VersatiaSubclassEnhancement(\"$processorClass\") on $carrierName must set enhancementPhase to a phase other than _NULL")
        return SubclassEnhancement(processorClass, phase, subclassOf.toClassName())
    }

    private fun resolve(enhancement: KSAnnotation, carrier: Link, usage: KSNode): Enhancement? {
        val carrierName = carrier.declaration.simpleName.asString()
        val reportAt = if (carrier.declaration.origin == Origin.KOTLIN) carrier.declaration else usage
        val processorClass = (enhancement.argument("processorClass") as? String)?.takeIf { it.isNotBlank() }
            ?: return fail(reportAt, "@VersatiaEnhancement on $carrierName has no processorClass")
        val fixedPhase = enhancement.argument("enhancementPhase").asEventType()
        val paramRef = enhancement.argument("enhancementPhaseParamRefName") as? String ?: ""

        val phase = when {
            fixedPhase != EventType._NULL && paramRef.isNotEmpty() -> return fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName sets both enhancementPhase and " +
                    "enhancementPhaseParamRefName; exactly one of them is required",
            )
            fixedPhase != EventType._NULL -> fixedPhase
            paramRef.isEmpty() -> return fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName declares no phase; set enhancementPhase " +
                    "or enhancementPhaseParamRefName",
            )
            else -> phaseFromProperty(carrier, paramRef, processorClass, reportAt) ?: return null
        }
        return Enhancement(processorClass, phase)
    }

    private fun phaseFromProperty(carrier: Link, paramRef: String, processorClass: String, reportAt: KSNode): EventType? {
        val carrierName = carrier.declaration.simpleName.asString()
        val property = carrier.declaration.getDeclaredProperties().firstOrNull { it.simpleName.asString() == paramRef }
            ?: return fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName refers to property \"$paramRef\", " +
                    "which $carrierName does not declare",
            )
        if (property.type.resolve().declaration.qualifiedName?.asString() != eventTypeName)
            return fail(reportAt, "Property \"$paramRef\" of $carrierName must be of type EventType to hold the phase")
        val phase = carrier.occurrence.argument(paramRef).asEventType()
        if (phase == EventType._NULL) return fail(
            carrier.occurrence,
            "@$carrierName must set $paramRef to a phase other than _NULL; it decides when the enhancement runs",
        )
        return phase
    }

    /** Several occurrences of a repeatable annotation compiled into a class arrive as one `Container` annotation. */
    private fun unwrapRepeatable(annotation: KSAnnotation, container: String) =
        if (annotation.declaration()?.qualifiedName?.asString() != container) listOf(annotation)
        else (annotation.argument("value") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>()

    private fun Any?.asEventType() = when (this) {
        is KSClassDeclaration -> simpleName.asString()
        is KSType -> declaration.simpleName.asString()
        is Enum<*> -> name
        else -> null
    }.let { entryName -> EventType.entries.firstOrNull { it.name == entryName } } ?: EventType._NULL

    private fun fail(node: KSNode, message: String): Nothing? {
        hadErrors = true
        logger.error(message, node)
        return null
    }
}

/** The value written for [name], or its default. */
internal fun KSAnnotation.argument(name: String) = arguments.firstOrNull { it.name?.asString() == name }?.value
    ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

internal fun KSAnnotation.declaration() = annotationType.resolve().declaration as? KSClassDeclaration
