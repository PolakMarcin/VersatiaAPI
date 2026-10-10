package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Collects the enhancements of the plugin's main class: the enhancement annotations written on it
 * and those written on its supertypes, `VersatiaPlugin` included, so that a base class gives every
 * plugin extending it an enhancement. See docs/enhancements.md.
 */
internal class PluginCollector(private val enhancements: EnhancementResolver, private val attributes: Attributes) {
    private val enhancementAnnotation = VersatiaEnhancement::class.qualifiedName!!

    fun collect(mainClass: KSClassDeclaration) =
        (sequenceOf(mainClass) + mainClass.getAllSuperTypes().mapNotNull { it.declaration as? KSClassDeclaration })
            .flatMap { it.annotations }
            .mapNotNull { annotation ->
                val chain = enhancements.chainOf(annotation)
                if (chain.isEmpty() || !enhancements.inherits(chain, enhancementAnnotation)) return@mapNotNull null
                EnhancedAnnotation(attributes.occurrence(annotation, mainClass), enhancements.enhancementsOf(chain, mainClass))
            }
            .filter { it.enhancements.isNotEmpty() }
            .toList()
}
