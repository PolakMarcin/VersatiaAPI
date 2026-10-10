package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.FunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName
import com.github.marcoral.versatia.di.BeanProcessors

internal data class BeanProblem(val node: KSNode?, val message: String)

/**
 * Compile-time checks over the beans, enhanced functions and other instantiated classes of one
 * plugin: unique names, resolvable dependencies, no cycles, and prototypes that take the plugin
 * only as a type every plugin has ([pluginTypes], the assignable types of `VersatiaPlugin`),
 * since a prototype's plugin parameter receives the consuming plugin. Mirrors the runtime's
 * resolution rules for the local beans; see docs/enhancements.md.
 */
internal object BeanGraphValidator {
    fun validate(
        declared: List<BeanDeclaration>,
        functions: List<FunctionDeclaration> = emptyList(),
        plugin: PluginDeclaration? = null,
        classes: List<ClassDeclaration> = emptyList(),
        pluginTypes: Set<ClassName> = emptySet(),
    ): List<BeanProblem> {
        val beans = listOfNotNull(plugin?.takeIf { it.isBean }?.asBean()) + declared
        val problems = mutableListOf<BeanProblem>()
        val edges = mutableMapOf<BeanDeclaration, MutableList<BeanDeclaration>>()

        beans.groupBy { it.name }.values.filter { it.size > 1 }.forEach { duplicates ->
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

        for (bean in beans) for (dependency in bean.dependencies) {
            when (val outcome = resolveLocally(dependency, bean.type.canonicalName, beans)) {
                is Resolution.Provider -> {
                    edges.getOrPut(bean) { mutableListOf() } += outcome.bean
                    if (bean.isPrototype && outcome.bean.type == plugin?.type && dependency.type !in pluginTypes) problems += BeanProblem(
                        bean.node,
                        "${bean.type.canonicalName} is a prototype, so its ${dependency.type.canonicalName} parameter receives the consuming plugin; " +
                            "declare it as VersatiaPlugin (or a supertype), which every plugin is",
                    )
                }
                is Resolution.Problem -> problems += BeanProblem(bean.node, outcome.message)
                Resolution.NotLocal -> Unit
            }
        }
        for (function in functions) for (parameter in function.parameters) {
            val outcome = resolveLocally(parameter.toDependency(), "${function.owner.canonicalName}.${function.name}()", beans)
            if (outcome is Resolution.Problem) problems += BeanProblem(function.node, outcome.message)
        }
        for (clazz in classes) for (parameter in clazz.parameters) {
            val outcome = resolveLocally(parameter.toDependency(), clazz.type.canonicalName, beans)
            if (outcome is Resolution.Problem) problems += BeanProblem(clazz.node, outcome.message)
        }

        problems += findCycles(beans, edges)
        return problems
    }

    private val BeanDeclaration.isPrototype get() = enhancements.any { it.processorClass == BeanProcessors.EXPOSE_PROTOTYPE }

    private sealed interface Resolution {
        class Provider(val bean: BeanDeclaration) : Resolution
        class Problem(val message: String) : Resolution
        object NotLocal : Resolution
    }

    /** The same rules the runtime applies, restricted to what is known at compile time. */
    private fun resolveLocally(dependency: DependencyRef, requester: String, beans: List<BeanDeclaration>): Resolution {
        val providers = beans.filter { dependency.type in it.assignableTypes }
        val qualifier = dependency.qualifier ?: return byType(dependency, requester, providers)
        val named = beans.filter { it.name == qualifier }
        return when (val match = named.firstOrNull { it in providers }) {
            null -> if (named.isEmpty()) Resolution.NotLocal else Resolution.Problem(
                "Bean \"$qualifier\" (${named.single().type.canonicalName}) does not provide " +
                    "${dependency.type.canonicalName}, required by $requester",
            )
            else -> Resolution.Provider(match)
        }
    }

    private fun byType(dependency: DependencyRef, requester: String, providers: List<BeanDeclaration>): Resolution {
        if (providers.isEmpty()) return Resolution.NotLocal
        if (providers.size == 1) return Resolution.Provider(providers.single())
        val primaries = providers.filter { it.primary }
        return when (primaries.size) {
            1 -> Resolution.Provider(primaries.single())
            0 -> Resolution.Problem(
                "Dependency ${dependency.type.canonicalName} of $requester is ambiguous; candidates: " +
                    providers.describe() + ". Add @Qualifier at the injection point or mark one bean @Primary",
            )
            else -> Resolution.Problem(
                "Dependency ${dependency.type.canonicalName} of $requester is ambiguous; several candidates are " +
                    "@Primary: " + primaries.describe(),
            )
        }
    }

    private fun List<BeanDeclaration>.describe() = joinToString { "\"${it.name}\" (${it.type.canonicalName})" }

    private fun findCycles(
        beans: List<BeanDeclaration>,
        edges: Map<BeanDeclaration, List<BeanDeclaration>>,
    ): List<BeanProblem> {
        val problems = mutableListOf<BeanProblem>()
        val finished = mutableSetOf<BeanDeclaration>()
        val path = mutableListOf<BeanDeclaration>()

        fun visit(bean: BeanDeclaration) {
            if (bean in finished) return
            val cycleStart = path.indexOf(bean)
            if (cycleStart >= 0) {
                val cycle = path.subList(cycleStart, path.size) + bean
                val description = cycle.joinToString(" -> ") { it.type.simpleName }
                problems += BeanProblem(cycle.first().node, "Dependency cycle: $description")
                return
            }
            path += bean
            edges[bean].orEmpty().forEach(::visit)
            path.removeAt(path.lastIndex)
            finished += bean
        }

        beans.forEach(::visit)
        return problems.distinctBy { it.message }
    }
}
