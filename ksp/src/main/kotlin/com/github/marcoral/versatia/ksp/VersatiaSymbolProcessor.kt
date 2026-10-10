package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.ksp.di.BeanCollector
import com.github.marcoral.versatia.ksp.di.BeanGraphValidator
import com.github.marcoral.versatia.ksp.lifecycle.FunctionCollector
import com.github.marcoral.versatia.ksp.model.DescriptorGenerator
import com.github.marcoral.versatia.ksp.model.DescriptorModel
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.kotlinpoet.ksp.writeTo
import com.google.devtools.ksp.getClassDeclarationByName

class VersatiaSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        VersatiaSymbolProcessor(environment).also(environment.registerProcessorForNewFeatures)
}

internal class VersatiaSymbolProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true

        val files = resolver.getNewFiles().toList()
        val mainClass = MainClassLocator(environment).locate(files, resolver) ?: return emptyList()

        val enhancements = EnhancementResolver(environment.logger)
        val attributes = Attributes(enhancements, environment.logger)
        val beanCollector = BeanCollector(environment.logger, mainClass, enhancements, attributes)
        val beans = beanCollector.collect(files)
        val plugin = beanCollector.describePlugin(PluginCollector(enhancements, attributes).collect(mainClass))
        val functionCollector = FunctionCollector(environment.logger, beans.map { it.type }.toSet(), enhancements, attributes)
        val functions = functionCollector.collect(files)

        val pluginTypes = resolver.getClassDeclarationByName(VERSATIA_PLUGIN)?.assignableTypes()?.toSet().orEmpty()
        val problems = BeanGraphValidator.validate(beans, functions, plugin, pluginTypes)
        problems.forEach { environment.logger.error(it.message, it.node) }
        val failed = enhancements.hadErrors || attributes.hadErrors || beanCollector.hadErrors || functionCollector.hadErrors
        if (failed || problems.isNotEmpty()) return emptyList()

        val model = DescriptorModel(
            packageName = mainClass.packageName.asString(),
            beans = beans.sortedBy { it.type.canonicalName },
            functions = functions.sortedWith(compareBy({ it.owner.canonicalName }, { it.name })),
            plugin = plugin,
        )
        DescriptorGenerator.generate(model)
            .writeTo(environment.codeGenerator, Dependencies(aggregating = true, *files.toTypedArray()))
        return emptyList()
    }

    companion object {
        const val OPTION_PLUGIN_YML = "versatia.pluginYml"
    }
}
