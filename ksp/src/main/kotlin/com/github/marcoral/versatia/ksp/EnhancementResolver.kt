package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.VersatiaSubclassEnhancement
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.SubclassEnhancement
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Walks the annotations an annotation *inherits* (its meta-annotations, transitively) and resolves
 * every `@VersatiaEnhancement` and `@VersatiaSubclassEnhancement` found on the way, including the
 * phase it runs at. See docs/enhancements.md.
 */
internal class EnhancementResolver(private val reporter: Reporter) {
    private val enhancementName = VersatiaEnhancement::class.qualifiedName!!
    private val containerName = "$enhancementName.Container"
    private val subclassName = VersatiaSubclassEnhancement::class.qualifiedName!!
    private val subclassContainerName = "$subclassName.Container"
    private val phaseName = Phase::class.qualifiedName!!

    /** A visited annotation class together with the occurrence through which it was reached. */
    class Link(val declaration: KSClassDeclaration, val occurrence: KSAnnotation)

    /** An enhancement annotation written on a function or property, with what it resolves to. */
    class MemberEnhancement(val annotation: KSAnnotation, val chain: List<Link>, val enhancements: List<Enhancement>)

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

    /** Whether the annotation of [chain] is an enhancement: it inherits `@VersatiaEnhancement`. */
    fun isEnhancement(chain: List<Link>) = inherits(chain, enhancementName)

    /** Whether any annotation on [annotated] is an enhancement. */
    fun isEnhanced(annotated: KSAnnotated) = annotated.annotations.any { isEnhancement(chainOf(it)) }

    /**
     * The nearest member of the plugin's sources that [member] overrides and that is enhanced too:
     * both elements would reach the override through virtual dispatch, so it would run twice.
     */
    fun enhancedOverrideeOf(member: KSDeclaration) =
        generateSequence(member.overridee()) { it.overridee() }.firstOrNull { it.containingFile != null && isEnhanced(it) }

    private fun KSDeclaration.overridee() = when (this) {
        is KSFunctionDeclaration -> findOverridee()
        is KSPropertyDeclaration -> findOverridee()
        else -> null
    }

    /** The enhancement annotations on a function or property, each resolved; errors point at [member]. */
    fun memberEnhancementsOf(member: KSAnnotated) = member.annotations.mapNotNull { annotation ->
        val chain = chainOf(annotation)
        if (isEnhancement(chain)) MemberEnhancement(annotation, chain, enhancementsOf(chain, member)) else null
    }.toList()

    /** Whether some annotation along [chain] carries a `@VersatiaEnhancement`, resolvable or not. */
    fun declaresEnhancement(chain: List<Link>) = chain.any { link -> declarationsOn(link, enhancementName, containerName).isNotEmpty() }

    /** The processor classes named along [chain], read without validation or reports. */
    fun processorNamesOf(chain: List<Link>) =
        chain.flatMap { link -> declarationsOn(link, enhancementName, containerName).mapNotNull { it.argument("processorClass") as? String } }

    /**
     * Enhancements declared along [chain]. [usage] is where errors are reported when the carrier
     * annotation comes from the classpath and has no source location of its own.
     */
    fun enhancementsOf(chain: List<Link>, usage: KSNode) =
        chain.flatMap { link -> declarationsOn(link, enhancementName, containerName).mapNotNull { resolve(it, link, usage) } }

    /** Subclass enhancements declared along [chain]; errors are reported like in [enhancementsOf]. */
    fun subclassEnhancementsOf(chain: List<Link>, usage: KSNode) =
        chain.flatMap { link -> declarationsOn(link, subclassName, subclassContainerName).mapNotNull { resolveSubclass(it, link, usage) } }

    private fun declarationsOn(link: Link, annotationName: String, container: String) = link.declaration.annotations
        .flatMap { unwrapRepeatable(it, container) }
        .filter { it.declaration()?.qualifiedName?.asString() == annotationName }
        .toList()

    /** What both declaration kinds share: the carrier's name, where to report and the processor class. */
    private class Declaration(val carrierName: String, val reportAt: KSNode, val processorClass: String?)

    private fun declaration(enhancement: KSAnnotation, carrier: Link, usage: KSNode) = Declaration(
        carrierName = carrier.declaration.simpleName.asString(),
        reportAt = if (carrier.declaration.origin == Origin.KOTLIN) carrier.declaration else usage,
        processorClass = (enhancement.argument("processorClass") as? String)?.takeIf { it.isNotBlank() },
    )

    private fun resolveSubclass(enhancement: KSAnnotation, carrier: Link, usage: KSNode): SubclassEnhancement? {
        val declaration = declaration(enhancement, carrier, usage)
        val carrierName = declaration.carrierName
        val processorClass = declaration.processorClass
            ?: return reporter.fail(declaration.reportAt, "@VersatiaSubclassEnhancement on $carrierName has no processorClass")
        val subclassOf = ((enhancement.argument("subclassOf") as? KSType)?.expanded()?.declaration as? KSClassDeclaration)
            ?: return reporter.fail(declaration.reportAt, "@VersatiaSubclassEnhancement(\"$processorClass\") on $carrierName names no class in subclassOf")
        val phase = enhancement.argument("enhancementPhase").asPhase()
        if (phase == Phase.UNSET)
            return reporter.fail(declaration.reportAt, "@VersatiaSubclassEnhancement(\"$processorClass\") on $carrierName must set enhancementPhase to a phase other than UNSET")
        return SubclassEnhancement(processorClass, phase, subclassOf.toClassName())
    }

    private fun resolve(enhancement: KSAnnotation, carrier: Link, usage: KSNode): Enhancement? {
        val declaration = declaration(enhancement, carrier, usage)
        val carrierName = declaration.carrierName
        val reportAt = declaration.reportAt
        val processorClass = declaration.processorClass ?: return reporter.fail(reportAt, "@VersatiaEnhancement on $carrierName has no processorClass")
        val fixedPhase = enhancement.argument("enhancementPhase").asPhase()
        val paramRef = enhancement.argument("enhancementPhaseParamRefName") as? String ?: ""

        val phase = when {
            fixedPhase != Phase.UNSET && paramRef.isNotEmpty() -> return reporter.fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName sets both enhancementPhase and " +
                    "enhancementPhaseParamRefName; exactly one of them is required",
            )
            fixedPhase != Phase.UNSET -> fixedPhase
            paramRef.isEmpty() -> return reporter.fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName declares no phase; set enhancementPhase " +
                    "or enhancementPhaseParamRefName",
            )
            else -> phaseFromProperty(carrier, paramRef, processorClass, reportAt) ?: return null
        }
        return Enhancement(processorClass, phase)
    }

    private fun phaseFromProperty(carrier: Link, paramRef: String, processorClass: String, reportAt: KSNode): Phase? {
        val carrierName = carrier.declaration.simpleName.asString()
        val property = carrier.declaration.getDeclaredProperties().firstOrNull { it.simpleName.asString() == paramRef }
            ?: return reporter.fail(
                reportAt,
                "@VersatiaEnhancement(\"$processorClass\") on $carrierName refers to property \"$paramRef\", " +
                    "which $carrierName does not declare",
            )
        if (property.type.resolve().expanded().declaration.qualifiedName?.asString() != phaseName)
            return reporter.fail(reportAt, "Property \"$paramRef\" of $carrierName must be of type Phase to hold the phase")
        val phase = carrier.occurrence.argument(paramRef).asPhase()
        if (phase == Phase.UNSET) return reporter.fail(
            carrier.occurrence,
            "@$carrierName must set $paramRef to a phase other than UNSET; it decides when the enhancement runs",
        )
        return phase
    }

    /** Several occurrences of a repeatable annotation compiled into a class arrive as one `Container` annotation. */
    private fun unwrapRepeatable(annotation: KSAnnotation, container: String) =
        if (annotation.declaration()?.qualifiedName?.asString() != container) listOf(annotation)
        else (annotation.argument("value") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>()

    private fun Any?.asPhase() = enumEntryName(this).let { entryName -> Phase.entries.firstOrNull { it.name == entryName } } ?: Phase.UNSET
}

/** The value written for [name], or its default. */
internal fun KSAnnotation.argument(name: String) = arguments.firstOrNull { it.name?.asString() == name }?.value
    ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

internal fun KSAnnotation.declaration() = annotationType.resolve().takeUnless { it.isError }?.expanded()?.declaration as? KSClassDeclaration
