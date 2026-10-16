package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.FunctionDeclaration
import com.github.marcoral.versatia.ksp.model.ParameterDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.asClassName

internal data class BeanProblem(val node: KSNode?, val message: String)

/**
 * Compile-time checks over the beans, enhanced functions and other instantiated classes of one
 * plugin: unique names, resolvable dependencies, no cycles, and prototypes that take the plugin
 * only as a type every plugin has ([pluginTypes], the assignable types of `VersatiaPlugin`),
 * since a prototype's plugin parameter receives the consuming plugin. Mirrors the runtime's
 * resolution rules for the local beans; a dependency no local bean provides is left to the
 * exposed beans of other plugins unless [unprovidable] says why none can provide it, and an
 * optional constructor parameter may have no provider at all. A local provider skipped by
 * `@ConditionalOnPlugin` does not exist: another provider stands in where its condition does not
 * hold, and a dependency all of whose providers may be skipped is an error. See
 * docs/enhancements.md.
 */
internal object BeanGraphValidator {
    fun validate(
        declared: List<BeanDeclaration>,
        functions: List<FunctionDeclaration>,
        plugin: PluginDeclaration?,
        classes: List<ClassDeclaration>,
        pluginTypes: Set<ClassName>,
        unprovidable: (ClassName) -> String?,
    ): List<BeanProblem> {
        val beans = listOfNotNull(plugin?.takeIf { it.isBean }?.asBean()) + declared
        val graph = Graph(beans, plugin, pluginTypes, unprovidable)
        graph.checkNames()
        graph.checkBeanDependencies()
        for (function in functions) graph.checkParameters(
            function.parameters,
            "${function.owner.canonicalName}.${function.name}()",
            (function.annotations + function.classAnnotations).requiredPlugins(),
            function.node,
        )
        for (clazz in classes) graph.checkParameters(clazz.parameters, clazz.type.canonicalName, clazz.annotations.requiredPlugins(), clazz.node)
        graph.checkCycles()
        return graph.problems
    }

    private class Graph(
        private val beans: List<BeanDeclaration>,
        private val plugin: PluginDeclaration?,
        private val pluginTypes: Set<ClassName>,
        private val unprovidable: (ClassName) -> String?,
    ) {
        val problems = mutableListOf<BeanProblem>()
        private val edges = mutableMapOf<BeanDeclaration, MutableList<BeanDeclaration>>()

        fun checkNames() = beans.groupBy { it.name }.values.filter { it.size > 1 }.forEach { duplicates ->
            if (plugin != null && duplicates.any { it.type == plugin.type }) {
                duplicates.filter { it.type != plugin.type }.forEach { bean ->
                    problems += BeanProblem(
                        bean.node,
                        "Bean name \"${bean.name}\" is reserved for the plugin's main class; rename ${bean.type.canonicalName} with @Qualifier",
                    )
                }
                return@forEach
            }
            duplicates.drop(1).forEach { bean ->
                problems += BeanProblem(
                    bean.node,
                    "Bean name \"${bean.name}\" is used by ${duplicates.joinToString { it.type.canonicalName }}; " +
                        "names must be unique within a plugin, rename one with @Qualifier",
                )
            }
        }

        fun checkBeanDependencies() {
            for (bean in beans) {
                val requiredPlugins = bean.annotations.requiredPlugins()
                for (dependency in bean.dependencies) when (val outcome = resolve(dependency, bean.type.canonicalName, requiredPlugins)) {
                    is Resolution.Providers -> for (provider in outcome.beans) {
                        edges.getOrPut(bean) { mutableListOf() } += provider
                        if (bean.isPrototype && provider.type == plugin?.type && dependency.type !in pluginTypes) problems += BeanProblem(
                            bean.node,
                            "${bean.type.canonicalName} is a prototype, so its ${dependency.type.canonicalName} parameter receives the consuming plugin; " +
                                "declare it as VersatiaPlugin (or a supertype), which every plugin is",
                        )
                    }
                    is Resolution.Problem -> problems += BeanProblem(bean.node, outcome.message)
                }
            }
        }

        fun checkParameters(parameters: List<ParameterDeclaration>, requester: String, requiredPlugins: Set<String>, node: KSNode?) {
            for (parameter in parameters.filter { it.suppliedBy == null }) {
                val outcome = resolve(parameter.toDependency(), requester, requiredPlugins)
                if (outcome is Resolution.Problem) problems += BeanProblem(node, outcome.message)
            }
        }

        fun checkCycles() {
            val finished = mutableSetOf<BeanDeclaration>()
            val path = mutableListOf<BeanDeclaration>()
            val cycles = mutableListOf<BeanProblem>()

            fun visit(bean: BeanDeclaration) {
                if (bean in finished) return
                val cycleStart = path.indexOf(bean)
                if (cycleStart >= 0) {
                    val cycle = path.subList(cycleStart, path.size) + bean
                    cycles += BeanProblem(cycle.first().node, "Dependency cycle: ${cycle.joinToString(" -> ") { it.type.canonicalName }}")
                    return
                }
                path += bean
                edges[bean].orEmpty().forEach(::visit)
                path.removeAt(path.lastIndex)
                finished += bean
            }

            beans.forEach(::visit)
            problems += cycles.distinctBy { it.message }
        }

        /**
         * The same rules the runtime applies, restricted to what is known at compile time: the
         * local providers the runtime may pick, or none when the dependency is left to the exposed
         * beans of other plugins or to the parameter's default value.
         */
        private fun resolve(dependency: DependencyRef, requester: String, requiredPlugins: Set<String>): Resolution {
            val qualifier = dependency.qualifier
            val assignable = beans.filter { dependency.type in it.assignableTypes }
            val providers = if (qualifier == null) assignable else {
                val named = beans.filter { it.name == qualifier }
                val matching = named.filter { it in assignable }
                if (named.isNotEmpty() && matching.isEmpty()) return Resolution.Problem(
                    "Bean \"$qualifier\" (${named.joinToString { it.type.canonicalName }}) does not provide " +
                        "${dependency.type.canonicalName}, required by $requester",
                )
                matching
            }
            if (providers.isNotEmpty()) return choose(dependency, requester, providers, requiredPlugins)
            val reason = unprovidable(dependency.type)?.takeUnless { dependency.optional } ?: return Resolution.Providers(emptyList())
            return Resolution.Problem("Dependency ${dependency.type.canonicalName} of $requester has no provider: $reason")
        }

        /**
         * The providers picked for every combination of the plugins their `@ConditionalOnPlugin`
         * names beyond [requiredPlugins]: a provider whose plugins are missing does not exist, so
         * another provider stands in, and `@Primary` counts only among those that exist.
         */
        private fun choose(dependency: DependencyRef, requester: String, providers: List<BeanDeclaration>, requiredPlugins: Set<String>): Resolution {
            val conditions = providers.associateWith { it.annotations.requiredPlugins() - requiredPlugins }
            val chosen = LinkedHashSet<BeanDeclaration>()
            for (enabled in combinationsOf(conditions.values.flatten().distinct())) {
                val present = providers.filter { enabled.containsAll(conditions.getValue(it)) }
                val primaries = present.filter { it.primary }
                when {
                    present.isEmpty() -> if (!dependency.optional) return Resolution.Problem(conditionalOnly(dependency, requester, conditions))
                    present.size == 1 -> chosen += present.single()
                    primaries.size == 1 -> chosen += primaries.single()
                    else -> return Resolution.Problem(ambiguity(dependency, requester, present, primaries, enabled))
                }
            }
            return Resolution.Providers(chosen.toList())
        }

        private fun conditionalOnly(dependency: DependencyRef, requester: String, conditions: Map<BeanDeclaration, Set<String>>): String {
            val advice = "make $requester conditional on the same plugins or declare a provider without the condition"
            val single = conditions.entries.singleOrNull()
            if (single != null) return "$requester depends on ${single.key.type.canonicalName}, which exists only with @ConditionalOnPlugin(${single.value.quoted()}); $advice"
            return "$requester depends on ${dependency.type.canonicalName}, whose providers all exist only with @ConditionalOnPlugin: " +
                conditions.entries.joinToString { (bean, plugins) -> "${bean.type.canonicalName} (${plugins.quoted()})" } + "; $advice"
        }

        private fun ambiguity(dependency: DependencyRef, requester: String, present: List<BeanDeclaration>, primaries: List<BeanDeclaration>, enabled: Set<String>): String {
            val condition = if (enabled.isEmpty()) "" else " while ${enabled.quoted()} ${if (enabled.size == 1) "is" else "are"} enabled"
            val prefix = "Dependency ${dependency.type.canonicalName} of $requester is ambiguous$condition"
            return if (primaries.isEmpty()) "$prefix; candidates: ${present.describe()}. Add @Qualifier at the injection point or mark one bean @Primary"
            else "$prefix; several candidates are @Primary: ${primaries.describe()}"
        }

        private val BeanDeclaration.isPrototype get() = enhancements.any { it.processorClass == BeanProcessors.EXPOSE_PROTOTYPE }

        private fun List<BeanDeclaration>.describe() = joinToString { "\"${it.name}\" (${it.type.canonicalName})" }

        private fun Collection<String>.quoted() = joinToString { "\"$it\"" }
    }

    /** Every subset of [plugins], the empty one first; with more than [MAX_COMBINED] plugins only none and all. */
    private fun combinationsOf(plugins: List<String>): List<Set<String>> =
        if (plugins.size > MAX_COMBINED) listOf(emptySet(), plugins.toSet())
        else (0 until (1 shl plugins.size)).map { mask -> plugins.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet() }

    private const val MAX_COMBINED = 10

    private val CONDITIONAL = ConditionalOnPlugin::class.asClassName()

    private fun List<AnnotationRef>.requiredPlugins() =
        filter { it.type == CONDITIONAL }.flatMap { it.arguments["value"] as? List<*> ?: emptyList() }.filterIsInstance<String>().toSet()

    private sealed interface Resolution {
        class Providers(val beans: List<BeanDeclaration>) : Resolution
        class Problem(val message: String) : Resolution
    }
}
