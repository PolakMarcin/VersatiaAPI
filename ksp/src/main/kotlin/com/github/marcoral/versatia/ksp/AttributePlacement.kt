package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.FriendlyName
import com.github.marcoral.versatia.VersatiaEnhancement
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode

/**
 * Checks the attributes the runtime reads itself, rather than a processor: `@FriendlyName` belongs
 * on the main class and names one word; `@ConditionalOnPlugin` belongs on an enhanced class, a
 * class declaring enhanced functions or such a function, never on the main class, and names at
 * least one plugin. See docs/enhancements.md.
 */
internal class AttributePlacement(private val logger: KSPLogger, private val enhancements: EnhancementResolver) {
    private var failed = false

    val hadErrors get() = failed

    private val friendlyName = FriendlyName::class.qualifiedName!!
    private val conditional = ConditionalOnPlugin::class.qualifiedName!!
    private val enhancementAnnotation = VersatiaEnhancement::class.qualifiedName!!

    /** [enhancedClasses] are the qualified names of the classes that are elements on their own: beans and subclass elements. */
    fun validate(files: List<KSFile>, mainClass: KSClassDeclaration, enhancedClasses: Set<String>) {
        val mainName = mainClass.qualifiedName?.asString()
        for (clazz in files.flatMap { it.declarations.flatMap(::classesIn) }) {
            val name = clazz.simpleName.asString()
            val isMain = clazz.qualifiedName?.asString() == mainName
            clazz.occurrenceOf(friendlyName)?.let { occurrence ->
                if (!isMain) fail(clazz, "@FriendlyName on $name has no effect: it belongs on the plugin's main class")
                else if (!(occurrence.argument("value") as? String).isWord()) fail(clazz, "@FriendlyName on $name must be one word without spaces")
            }
            clazz.occurrenceOf(conditional)?.let { occurrence ->
                when {
                    isMain -> fail(clazz, "@ConditionalOnPlugin cannot apply to $name, the plugin's main class")
                    clazz.qualifiedName?.asString() !in enhancedClasses && clazz.getDeclaredFunctions().none { it.isEnhanced() } ->
                        fail(clazz, "@ConditionalOnPlugin on $name has no effect: $name is not enhanced and declares no enhanced function")
                    else -> checkPlugins(occurrence, clazz, name)
                }
            }
            for (function in clazz.getDeclaredFunctions()) function.occurrenceOf(conditional)?.let { occurrence ->
                val label = "$name.${function.simpleName.asString()}()"
                if (!function.isEnhanced()) fail(function, "@ConditionalOnPlugin on $label has no effect: the function is not enhanced")
                else checkPlugins(occurrence, function, label)
            }
        }
        for (function in files.flatMap { it.declarations.filterIsInstance<KSFunctionDeclaration>() }) if (function.occurrenceOf(conditional) != null)
            fail(function, "@ConditionalOnPlugin on ${function.simpleName.asString()}() has no effect: the function is not enhanced")
    }

    private fun checkPlugins(occurrence: KSAnnotation, node: KSNode, label: String) {
        val plugins = when (val value = occurrence.argument("value")) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }
        if (plugins.isEmpty() || plugins.any { !(it as? String).isWord() }) fail(node, "@ConditionalOnPlugin on $label must name plugins, one word each")
    }

    private fun KSFunctionDeclaration.isEnhanced() = annotations.any { enhancements.inherits(enhancements.chainOf(it), enhancementAnnotation) }

    private fun KSAnnotated.occurrenceOf(annotationName: String) = annotations.firstOrNull { it.declaration()?.qualifiedName?.asString() == annotationName }

    private fun String?.isWord() = !isNullOrBlank() && none { it.isWhitespace() }

    private fun fail(node: KSNode, message: String) {
        failed = true
        logger.error(message, node)
    }
}
