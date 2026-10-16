package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import java.io.File

/**
 * Finds the plugin's main class: by default the most derived concrete `JavaPlugin` subclass in
 * the sources, or the `main:` entry of the `plugin.yml` named by
 * [VersatiaSymbolProcessor.OPTION_PLUGIN_YML]. Either way the class must be one Bukkit can create:
 * a public, non-inner, non-generic `JavaPlugin` subclass with a no-argument constructor.
 */
internal class MainClassLocator(private val options: Map<String, String>, private val reporter: Reporter) {
    fun locate(sources: Sources, resolver: Resolver): KSClassDeclaration? {
        val pluginYml = options[VersatiaSymbolProcessor.OPTION_PLUGIN_YML]
        val main = (if (pluginYml != null) fromPluginYml(pluginYml, resolver) else fromSources(sources)) ?: return null
        val problem = main.unloadableReason() ?: return main
        return reporter.fail(main, "Bukkit cannot load the main class ${main.qualifiedName?.asString()}: $problem")
    }

    private fun fromSources(sources: Sources): KSClassDeclaration? {
        val plugins = sources.classes.filter { it.extendsJavaPlugin() && !it.isAbstract() }
        // An open plugin class that another one extends is a base class, not the main class
        val extended = plugins.flatMap { plugin -> plugin.getAllSuperTypes().mapNotNull { it.declaration.qualifiedName?.asString() } }.toSet()
        val candidates = plugins.filter { it.qualifiedName?.asString() !in extended }
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> reporter.fail(null, "No class extending $JAVA_PLUGIN found in the sources, so the plugin's main class is unknown. $HINT")
            else -> reporter.fail(null, "Several classes extend $JAVA_PLUGIN: ${candidates.joinToString { it.qualifiedName!!.asString() }}. $HINT")
        }
    }

    private fun fromPluginYml(path: String, resolver: Resolver): KSClassDeclaration? {
        val file = File(path)
        if (!file.isFile) return reporter.fail(null, "plugin.yml not found at ${file.absolutePath}")
        val name = PluginYml.mainClassOf(file.readText()) ?: return reporter.fail(null, "No top-level `main:` entry in ${file.absolutePath}")
        return resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            ?: reporter.fail(null, "Main class $name from ${file.absolutePath} is neither in the sources nor on the classpath")
    }

    /** Bukkit calls the no-argument constructor reflectively, from outside the plugin's package. */
    private fun KSClassDeclaration.unloadableReason() = when {
        classKind != ClassKind.CLASS -> "it is ${classKind.description()}, but Bukkit creates the instance of a class itself"
        !extendsJavaPlugin() -> "it does not extend $JAVA_PLUGIN"
        isAbstract() -> "it is abstract"
        Modifier.INNER in modifiers -> "it is an inner class, which needs an instance of its outer class"
        typeParameters.isNotEmpty() -> "it has type parameters"
        getVisibility() == Visibility.PRIVATE || getVisibility() == Visibility.LOCAL -> "it is private"
        !hasPublicNoArgumentConstructor() -> "it has no public constructor without arguments"
        else -> null
    }

    private fun KSClassDeclaration.hasPublicNoArgumentConstructor(): Boolean {
        val visible = getConstructors().filter { it.getVisibility() == Visibility.PUBLIC || it.getVisibility() == Visibility.INTERNAL }.toList()
        val primary = primaryConstructor
        return visible.any { it.parameters.isEmpty() || it == primary && it.parameters.all { parameter -> parameter.hasDefault } }
    }

    private fun ClassKind.description() = when (this) {
        ClassKind.OBJECT -> "an object"
        ClassKind.INTERFACE -> "an interface"
        ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> "an enum"
        ClassKind.ANNOTATION_CLASS -> "an annotation class"
        ClassKind.CLASS -> "a class"
    }

    private companion object {
        const val HINT = "Set the KSP option ${VersatiaSymbolProcessor.OPTION_PLUGIN_YML} to the path of plugin.yml to name it explicitly."
    }
}
