package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/** Whether generated code in the plugin's own module may refer to this declaration. */
internal fun KSDeclaration.isReachable(): Boolean = getVisibility() in REACHABLE && parentDeclaration?.isReachable() != false

private val REACHABLE = setOf(Visibility.PUBLIC, Visibility.INTERNAL)

/** The class itself and every supertype except `Any`. */
internal fun KSClassDeclaration.assignableTypes() = listOf(toClassName()) + getAllSuperTypes()
        .mapNotNull { (it.declaration as? KSClassDeclaration)?.toClassName() }
        .filter { it != ANY }
        .toList()

private val ANY = ClassName("kotlin", "Any")

internal const val JAVA_PLUGIN = "org.bukkit.plugin.java.JavaPlugin"

/** Named, not referenced: the processor's classpath has no Paper API, so the class must never load. */
internal const val VERSATIA_PLUGIN = "com.github.marcoral.versatia.VersatiaPlugin"

internal fun KSClassDeclaration.extendsJavaPlugin() = getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == JAVA_PLUGIN }

/** [declaration] and every class nested in it, at any depth. */
internal fun classesIn(declaration: KSDeclaration): Sequence<KSClassDeclaration> =
    if (declaration !is KSClassDeclaration) emptySequence() else sequenceOf(declaration) + declaration.declarations.flatMap(::classesIn)
