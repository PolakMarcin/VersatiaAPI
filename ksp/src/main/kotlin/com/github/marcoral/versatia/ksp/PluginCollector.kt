package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.VersatiaDisabler
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.VersatiaSubclassEnhancement
import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Collects the enhancement annotations of the plugin's main class: those written on it and on
 * its supertypes, `VersatiaPlugin` included, so that a base class gives every plugin extending it
 * an enhancement. Each annotation may enhance the main class itself (`@VersatiaEnhancement`) and
 * the subclasses of some type (`@VersatiaSubclassEnhancement`). An annotation with
 * `@VersatiaDisabler` switches such an annotation off; the nearest declaration in the lineage
 * wins. See docs/enhancements.md.
 */
internal class PluginCollector(
    private val logger: KSPLogger,
    private val enhancements: EnhancementResolver,
    private val attributes: Attributes,
) {
    private var failed = false

    val hadErrors get() = failed

    private val enhancementAnnotation = VersatiaEnhancement::class.qualifiedName!!
    private val subclassAnnotation = VersatiaSubclassEnhancement::class.qualifiedName!!
    private val beanAnnotation = Bean::class.qualifiedName!!
    private val disablerAnnotation = VersatiaDisabler::class.qualifiedName!!

    fun collect(mainClass: KSClassDeclaration): List<EnhancedAnnotation> {
        val decided = mutableSetOf<String>()
        val carried = mutableSetOf<String>()
        val switchedOff = mutableListOf<Pair<String, KSNode>>()
        val result = mutableListOf<EnhancedAnnotation>()
        for (declaration in lineage(mainClass)) {
            val reportAt = if (declaration.origin == Origin.KOTLIN) declaration else mainClass
            val name = declaration.simpleName.asString()
            val disabled = switchesOn(declaration, reportAt)
            for ((annotation, chain) in enhancementsOn(declaration)) {
                val enhancement = chain.first().declaration.qualifiedName!!.asString()
                carried += enhancement
                if (enhancement in disabled) fail(reportAt, "$name both carries and disables @${annotation.shortName.asString()}")
                if (decided.add(enhancement)) result += EnhancedAnnotation(
                    annotation = attributes.occurrence(annotation, mainClass),
                    enhancements = enhancements.enhancementsOf(chain, mainClass),
                    subclassEnhancements = enhancements.subclassEnhancementsOf(chain, mainClass),
                    origin = declaration.toClassName(),
                )
            }
            decided += disabled
            switchedOff += disabled.map { it to reportAt }
        }
        for ((enhancement, node) in switchedOff) if (enhancement !in carried) {
            val main = mainClass.simpleName.asString()
            fail(node, "$main disables @${enhancement.substringAfterLast('.')}, which neither $main nor its supertypes carry")
        }
        return result.filter { it.enhancements.isNotEmpty() || it.subclassEnhancements.isNotEmpty() }
    }

    /** Plugin enhancements and switches belong on the main class or on a base class of it; anywhere else they do nothing. */
    fun validate(files: List<KSFile>) {
        for (clazz in files.flatMap { it.declarations.flatMap(::classesIn) }) {
            if (clazz.classKind == ClassKind.ANNOTATION_CLASS || clazz.extendsJavaPlugin()) continue
            for (annotation in clazz.annotations) {
                val chain = enhancements.chainOf(annotation)
                val plugin = chain.isNotEmpty() && !enhancements.inherits(chain, beanAnnotation) &&
                    (enhancements.inherits(chain, enhancementAnnotation) || enhancements.inherits(chain, subclassAnnotation))
                if (plugin || switchesOf(annotation).isNotEmpty()) {
                    fail(clazz, "@${annotation.shortName.asString()} on ${clazz.simpleName.asString()} has no effect: it belongs on the plugin's main class or on a base class of it")
                }
            }
        }
    }

    /** The main class, its superclasses nearest first, then the interfaces. */
    private fun lineage(mainClass: KSClassDeclaration): List<KSClassDeclaration> {
        val classes = generateSequence(mainClass) { declaration ->
            declaration.superTypes.mapNotNull { it.resolve().declaration as? KSClassDeclaration }.firstOrNull { it.classKind == ClassKind.CLASS }
        }.toList()
        val names = classes.map { it.qualifiedName?.asString() }.toSet()
        return classes + mainClass.getAllSuperTypes().mapNotNull { it.declaration as? KSClassDeclaration }.filter { it.qualifiedName?.asString() !in names }
    }

    private fun enhancementsOn(declaration: KSClassDeclaration) = declaration.annotations.mapNotNull { annotation ->
        val chain = enhancements.chainOf(annotation)
        val enhancing = chain.isNotEmpty() && (enhancements.inherits(chain, enhancementAnnotation) || enhancements.inherits(chain, subclassAnnotation))
        if (enhancing) annotation to chain else null
    }

    /** The enhancement annotations switched off by the switches on [declaration] that are set to true. */
    private fun switchesOn(declaration: KSClassDeclaration, reportAt: KSNode) = declaration.annotations.flatMap { annotation ->
        val switches = switchesOf(annotation)
        if (switches.isEmpty()) return@flatMap emptyList()
        val on = switches.filter { (parameter, _) -> annotation.argument(parameter) == true }
        if (on.isEmpty()) fail(reportAt, "@${annotation.shortName.asString()} on ${declaration.simpleName.asString()} switches nothing off")
        on.map { (_, enhancement) -> enhancement }
    }

    /** The `@VersatiaDisabler` parameters of [annotation]'s class: parameter name to the enhancement annotation it switches off. */
    private fun switchesOf(annotation: KSAnnotation) = annotation.declaration()?.primaryConstructor?.parameters.orEmpty().mapNotNull { parameter ->
        val disabler = parameter.annotations.firstOrNull { it.declaration()?.qualifiedName?.asString() == disablerAnnotation } ?: return@mapNotNull null
        val enhancement = (disabler.argument("enhancement") as? KSType)?.declaration?.qualifiedName?.asString() ?: return@mapNotNull null
        parameter.name!!.asString() to enhancement
    }

    private fun fail(node: KSNode, message: String) {
        failed = true
        logger.error(message, node)
    }
}
