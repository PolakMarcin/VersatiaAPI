package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import java.io.File

/**
 * Finds the plugin's main class: by default the single concrete `JavaPlugin` subclass in the
 * sources, or the `main:` entry of the `plugin.yml` named by [VersatiaSymbolProcessor.OPTION_PLUGIN_YML].
 */
internal class MainClassLocator(private val environment: SymbolProcessorEnvironment) {
    private val logger get() = environment.logger

    fun locate(files: List<KSFile>, resolver: Resolver): KSClassDeclaration? {
        environment.options[VersatiaSymbolProcessor.OPTION_PLUGIN_YML]?.let { return fromPluginYml(it, resolver) }

        val candidates = files.flatMap { it.declarations.flatMap(::classesIn) }
            .filter { it.classKind == ClassKind.CLASS && Modifier.ABSTRACT !in it.modifiers && it.extendsJavaPlugin() }
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> fail("No class extending $JAVA_PLUGIN found in the sources, so the plugin's main class is unknown. $HINT")
            else -> fail("Several classes extend $JAVA_PLUGIN: ${candidates.joinToString { it.qualifiedName!!.asString() }}. $HINT")
        }
    }

    private fun fromPluginYml(path: String, resolver: Resolver): KSClassDeclaration? {
        val file = File(path)
        if (!file.isFile) return fail("plugin.yml not found at $path")
        val name = PluginYml.mainClassOf(file.readText()) ?: return fail("No `main:` entry in $path")
        return resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            ?: fail("Main class $name from $path is neither in the sources nor on the classpath")
    }

    private fun fail(message: String): Nothing? {
        logger.error(message)
        return null
    }

    private companion object {
        const val HINT = "Set the KSP option ${VersatiaSymbolProcessor.OPTION_PLUGIN_YML} to the path of plugin.yml to name it explicitly."
    }
}
