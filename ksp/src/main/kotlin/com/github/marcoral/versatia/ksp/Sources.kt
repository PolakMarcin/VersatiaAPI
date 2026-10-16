package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference

/** The plugin's source files, walked once: every class at any depth and the top-level members. */
internal class Sources(val files: List<KSFile>) {
    val classes = files.flatMap { it.declarations.flatMap(::classesIn) }
    val topLevelFunctions = files.flatMap { it.declarations.filterIsInstance<KSFunctionDeclaration>() }
    val topLevelProperties = files.flatMap { it.declarations.filterIsInstance<KSPropertyDeclaration>() }
    val classNames = classes.mapNotNull { it.qualifiedName?.asString() }.toSet()

    /**
     * The type references the processor reads that do not resolve: supertypes, constructor and
     * function parameters and property types. A typo or a class another processor has yet to
     * generate; the descriptor waits until they resolve.
     */
    fun unresolvedTypes(): List<KSTypeReference> {
        val references = classes.flatMap { clazz ->
            clazz.superTypes.toList() +
                clazz.declarations.filterIsInstance<KSFunctionDeclaration>().flatMap { function -> function.parameters.map { it.type } }.toList() +
                clazz.declarations.filterIsInstance<KSPropertyDeclaration>().map { it.type }.toList()
        } + topLevelFunctions.flatMap { function -> function.parameters.map { it.type } } + topLevelProperties.map { it.type }
        return references.filter { it.resolve().hasError() }
    }

    private fun KSType.hasError(): Boolean = isError || arguments.any { it.type?.resolve()?.hasError() == true }
}
