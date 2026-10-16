package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.ksp.config.PropertyCollector
import com.github.marcoral.versatia.ksp.di.BeanCollector
import com.github.marcoral.versatia.ksp.di.BeanGraphValidator
import com.github.marcoral.versatia.ksp.di.Injection
import com.github.marcoral.versatia.ksp.lifecycle.FunctionCollector
import com.github.marcoral.versatia.ksp.model.DescriptorGenerator
import com.github.marcoral.versatia.ksp.model.DescriptorModel
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.writeTo

class VersatiaSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        VersatiaSymbolProcessor(environment).also(environment.registerProcessorForNewFeatures)
}

/**
 * Generates the descriptor once per compilation. While a type the processor reads does not
 * resolve, it waits for the next round, in case another processor generates the type; when the
 * last round passes with the type still unresolved, [finish] reports it. See docs/enhancements.md.
 */
internal class VersatiaSymbolProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var done = false
    private var unresolved = emptyList<KSTypeReference>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        val sources = Sources(resolver.getAllFiles().toList())
        unresolved = sources.unresolvedTypes()
        if (unresolved.isNotEmpty()) return emptyList()
        done = true
        generate(sources, resolver)
        return emptyList()
    }

    override fun finish() {
        if (done) return
        for ((reference, name) in unresolved.map { it to it.toString().replace(ERROR_TYPE, "$1") }.distinctBy { (reference, name) -> reference.location to name })
            environment.logger.error("Type $name cannot be resolved, so the Versatia descriptor cannot be generated", reference)
    }

    private fun generate(sources: Sources, resolver: Resolver) {
        val reporter = Reporter(environment.logger)
        val mainClass = MainClassLocator(environment.options, reporter).locate(sources, resolver) ?: return

        val enhancements = EnhancementResolver(reporter)
        val attributes = Attributes(enhancements, reporter)
        val jvm = JvmSignatures(resolver)
        val injection = Injection(attributes, reporter, jvm)
        val pluginCollector = PluginCollector(reporter, enhancements, attributes)
        pluginCollector.validate(sources, mainClass)
        val beanCollector = BeanCollector(reporter, mainClass, enhancements, attributes, injection)
        val beans = beanCollector.collect(sources)
        val plugin = beanCollector.describePlugin(pluginCollector.collect(mainClass))
        val owners = Owners(pluginLineageOf(mainClass), beanCollector.scopedTypes)
        val functions = FunctionCollector(reporter, owners, enhancements, attributes, injection, jvm).collect(sources)
        val properties = PropertyCollector(reporter, owners, enhancements, attributes).collect(sources)
        val subclasses = SubclassCollector(reporter, mainClass, attributes, injection).collect(sources, plugin.enhancements)
        val subclassTypes = subclasses.mapNotNull { it.clazz?.type?.canonicalName }
        AttributePlacement(reporter, enhancements, attributes).validate(sources, mainClass, beanCollector.scopedTypes + subclassTypes)

        val pluginTypes = resolver.getClassDeclarationByName(VERSATIA_PLUGIN)?.assignableTypes()?.toSet().orEmpty()
        val unprovidable = Unprovidable(sources, mainClass, resolver, enhancements)
        BeanGraphValidator.validate(beans, functions, plugin, subclasses.mapNotNull { it.clazz }.distinct(), pluginTypes, unprovidable::reasonFor)
            .forEach { reporter.fail(it.node, it.message) }
        if (reporter.hadErrors) return

        val packageName = mainClass.packageName.asString()
        val model = DescriptorModel(
            packageName = packageName,
            beans = beans.sortedBy { it.type.canonicalName },
            functions = functions.sortedWith(compareBy({ it.owner.canonicalName }, { it.name })),
            plugin = plugin,
            subclasses = subclasses,
            properties = properties.sortedWith(compareBy({ it.owner.canonicalName }, { it.name })),
            shadowedNames = namesDeclaredIn(packageName, resolver),
        )
        DescriptorGenerator.generate(model)
            .writeTo(environment.codeGenerator, Dependencies(aggregating = true, *sources.files.toTypedArray()))
    }

    /** The main class and its superclasses: the classes whose members the plugin instance owns. */
    private fun pluginLineageOf(mainClass: KSClassDeclaration) = generateSequence(mainClass) { declaration ->
        declaration.superTypes.mapNotNull { it.resolve().expanded().declaration as? KSClassDeclaration }.firstOrNull { it.classKind == ClassKind.CLASS }
    }.mapNotNull { it.qualifiedName?.asString() }.toSet()

    @OptIn(KspExperimental::class)
    private fun namesDeclaredIn(packageName: String, resolver: Resolver) = resolver.getDeclarationsFromPackage(packageName)
        .filter { it is KSClassDeclaration || it is KSTypeAlias }
        .map { it.simpleName.asString() }
        .toSet()

    /**
     * Why no plugin can provide a type that no local bean provides: a type of the plugin's own
     * sources is invisible to the plugins loaded before it, and a final class without a scope
     * that exposes it is a bean nowhere.
     */
    private class Unprovidable(
        private val sources: Sources,
        private val mainClass: KSClassDeclaration,
        private val resolver: Resolver,
        private val enhancements: EnhancementResolver,
    ) {
        private val beanAnnotation = Bean::class.qualifiedName!!

        fun reasonFor(type: ClassName): String? {
            if (type.canonicalName == mainClass.qualifiedName?.asString())
                return "${type.simpleName} is the plugin's main class, which is a bean only with @PluginBean, as every VersatiaPlugin has"
            if (type.canonicalName in sources.classNames)
                return "${type.simpleName} is declared in this plugin, so no other plugin can provide it; give a class providing it a bean scope such as @LocalBean"
            val declaration = resolver.getClassDeclarationByName(type.canonicalName) ?: return null
            if (!declaration.isFinal() || declaration.isExposed()) return null
            return "${type.canonicalName} is final and no bean scope exposes it, so no plugin can provide it"
        }

        private fun KSClassDeclaration.isExposed() = annotations.any { annotation ->
            val chain = enhancements.chainOf(annotation)
            enhancements.inherits(chain, beanAnnotation) && enhancements.processorNamesOf(chain).any { it != BeanProcessors.LOCAL }
        }
    }

    companion object {
        const val OPTION_PLUGIN_YML = "versatia.pluginYml"

        /** How KSP renders a type it cannot resolve: `<ERROR TYPE: Clok>`. */
        private val ERROR_TYPE = Regex("<ERROR TYPE: ([^>]*)>")
    }
}
