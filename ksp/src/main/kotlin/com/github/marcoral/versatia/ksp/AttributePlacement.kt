package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.FriendlyName
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin

/**
 * Checks the attributes the runtime reads itself, rather than a processor: `@FriendlyName` belongs
 * on the main class and names one Brigadier word, so that `/versatia reload <name>` can address
 * it; `@ConditionalOnPlugin` belongs on an enhanced class, a class declaring enhanced functions or
 * properties, or such a function, never on the main class, and names at least one plugin. Both
 * count when inherited through an annotation class too, where their arguments are checked. Every
 * declaration and parameter is walked, so that an attribute reaching one twice is reported. See
 * docs/enhancements.md.
 */
internal class AttributePlacement(private val reporter: Reporter, private val enhancements: EnhancementResolver, private val attributes: Attributes) {
    private val friendlyName = FriendlyName::class.qualifiedName!!
    private val conditional = ConditionalOnPlugin::class.qualifiedName!!

    /** [enhancedClasses] are the qualified names of the classes that are elements on their own: beans and subclass elements. */
    fun validate(sources: Sources, mainClass: KSClassDeclaration, enhancedClasses: Set<String>) {
        val mainName = mainClass.qualifiedName?.asString()
        for (clazz in sources.classes) {
            val name = clazz.simpleName.asString()
            val isMain = clazz.qualifiedName?.asString() == mainName
            val reached = attributes.reaching(clazz)
            reached.firstOrNull { it.name == friendlyName }?.let { found ->
                if (clazz.classKind != ClassKind.ANNOTATION_CLASS && !isMain)
                    reporter.fail(clazz, "@FriendlyName on $name has no effect: it belongs on the plugin's main class")
                else checkFriendlyName(found, clazz, name)
            }
            val conditions = reached.filter { it.name == conditional }
            if (conditions.isNotEmpty()) when {
                clazz.classKind == ClassKind.ANNOTATION_CLASS -> checkPlugins(conditions, clazz, name)
                isMain -> reporter.fail(clazz, "@ConditionalOnPlugin cannot apply to $name, the plugin's main class")
                clazz.qualifiedName?.asString() !in enhancedClasses && clazz.declaresNothingEnhanced() ->
                    reporter.fail(clazz, "@ConditionalOnPlugin on $name has no effect: $name is not enhanced and declares no enhanced function or property")
                else -> checkPlugins(conditions, clazz, name)
            }
            for (function in clazz.getDeclaredFunctions()) {
                val functionConditions = function.conditions()
                if (functionConditions.isEmpty()) continue
                val label = "$name.${function.simpleName.asString()}()"
                if (!enhancements.isEnhanced(function)) reporter.fail(function, "@ConditionalOnPlugin on $label has no effect: the function is not enhanced")
                else checkPlugins(functionConditions, function, label)
            }
            clazz.getDeclaredProperties().forEach(attributes::reaching)
        }
        // An enhanced top-level function is reported by FunctionCollector
        for (function in sources.topLevelFunctions) if (function.conditions().isNotEmpty() && !enhancements.isEnhanced(function))
            reporter.fail(function, "@ConditionalOnPlugin on ${function.simpleName.asString()}() has no effect: the function is not enhanced")
        sources.topLevelProperties.forEach(attributes::reaching)
    }

    /** The `@ConditionalOnPlugin` occurrences reaching the function; its parameters are walked for repeated attributes on the way. */
    private fun KSFunctionDeclaration.conditions(): List<Attributes.Reached> {
        parameters.forEach(attributes::reaching)
        return attributes.reaching(this).filter { it.name == conditional }
    }

    private fun checkFriendlyName(found: Attributes.Reached, node: KSNode, label: String) {
        if (found.checkedHere() && !(found.annotation.argument("value") as? String).isCommandWord())
            reporter.fail(node, "@FriendlyName on $label must be one word of letters, digits and the characters _ . + -, so that commands can name it")
    }

    private fun checkPlugins(conditions: List<Attributes.Reached>, node: KSNode, label: String) =
        conditions.filter { it.checkedHere() }.forEach { checkPlugins(it.annotation, node, label) }

    /** An inherited occurrence is checked where it is written, on its annotation class, unless that class comes from the classpath. */
    private fun Attributes.Reached.checkedHere() = carrier == null || carrier.origin != Origin.KOTLIN

    private fun checkPlugins(occurrence: KSAnnotation, node: KSNode, label: String) {
        val plugins = when (val value = occurrence.argument("value")) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }
        if (plugins.isEmpty() || plugins.any { !(it as? String).isWord() }) reporter.fail(node, "@ConditionalOnPlugin on $label must name plugins, one word each")
    }

    private fun KSClassDeclaration.declaresNothingEnhanced() =
        getDeclaredFunctions().none { enhancements.isEnhanced(it) } && getDeclaredProperties().none { enhancements.isEnhanced(it) }

    private fun String?.isWord() = !isNullOrBlank() && none { it.isWhitespace() }

    /** What Brigadier's `word()` argument, which reads `/versatia reload <name>`, accepts. */
    private fun String?.isCommandWord() = this != null && COMMAND_WORD.matches(this)

    private companion object {
        val COMMAND_WORD = Regex("[0-9A-Za-z_.+-]+")
    }
}
