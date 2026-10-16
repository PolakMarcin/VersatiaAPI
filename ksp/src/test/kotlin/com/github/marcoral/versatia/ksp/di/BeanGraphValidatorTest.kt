package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.ksp.model.AnnotationRef
import com.github.marcoral.versatia.ksp.model.ClassDeclaration
import com.github.marcoral.versatia.ksp.model.EnhancedAnnotation
import com.github.marcoral.versatia.ksp.model.Enhancement
import com.github.marcoral.versatia.ksp.model.FunctionDeclaration
import com.github.marcoral.versatia.ksp.model.ParameterDeclaration
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeanGraphValidatorTest {
    private val localBean = AnnotationRef(ClassName("com.github.marcoral.versatia.di", "LocalBean"))
    private val local = listOf(Enhancement(BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY))

    private fun type(name: String) = ClassName("com.example", name)

    private fun bean(
        className: String,
        vararg parameters: ParameterDeclaration,
        provides: List<String> = emptyList(),
        name: String = className.replaceFirstChar { it.lowercaseChar() },
        primary: Boolean = false,
        enhancements: List<Enhancement> = local,
        onPlugins: List<String> = emptyList(),
    ) = BeanDeclaration(
        type = type(className),
        name = name,
        primary = primary,
        scope = localBean,
        enhancements = enhancements,
        annotations = conditional(onPlugins),
        parameters = parameters.toList(),
        assignableTypes = listOf(type(className)) + provides.map(::type),
        reachable = true,
    )

    private fun conditional(plugins: List<String>) =
        listOfNotNull(plugins.takeIf { it.isNotEmpty() }?.let { AnnotationRef(ClassName("com.github.marcoral.versatia", "ConditionalOnPlugin"), mapOf("value" to it)) })

    private fun dep(typeName: String, qualifier: String? = null, optional: Boolean = false) = ParameterDeclaration(
        type(typeName),
        listOfNotNull(qualifier?.let { AnnotationRef(QUALIFIER, mapOf("value" to it)) }),
        optional = optional,
    )

    private fun function(vararg parameters: ParameterDeclaration, classOnPlugins: List<String> = emptyList()) = FunctionDeclaration(
        owner = type("Main"),
        ownerKind = MemberOwner.PLUGIN,
        name = "start",
        annotation = AnnotationRef(ClassName("com.github.marcoral.versatia.lifecycle", "AutoInvoke")),
        enhancements = listOf(Enhancement("com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor", Phase.PLUGIN_START_ONLY)),
        annotations = emptyList(),
        parameters = parameters.toList(),
        reachable = true,
        classAnnotations = conditional(classOnPlugins),
    )

    private fun listener(className: String, vararg parameters: ParameterDeclaration) =
        ClassDeclaration(type(className), emptyList(), parameters.toList(), listOf(type(className)), reachable = true)

    private fun mainPlugin(vararg types: String) = PluginDeclaration(
        type("Main"),
        primary = false,
        annotations = emptyList(),
        assignableTypes = listOf(type("Main")) + types.map(::type),
        enhancements = listOf(EnhancedAnnotation(AnnotationRef(type("PluginBean")), listOf(Enhancement(BeanProcessors.PLUGIN, Phase.PLUGIN_START_ONLY)), origin = type("VersatiaPlugin"))),
    )

    private fun messages(
        beans: List<BeanDeclaration>,
        functions: List<FunctionDeclaration> = emptyList(),
        plugin: PluginDeclaration? = null,
        classes: List<ClassDeclaration> = emptyList(),
        pluginTypes: Set<ClassName> = emptySet(),
        unprovidable: (ClassName) -> String? = { null },
    ) = BeanGraphValidator.validate(beans, functions, plugin, classes, pluginTypes, unprovidable).map { it.message }

    @Test
    fun `accepts an acyclic graph with unresolved dependencies`() {
        val beans = listOf(bean("A", dep("B")), bean("B", dep("C")), bean("C", dep("FromOtherPlugin")))
        assertEquals(emptyList(), messages(beans))
    }

    @Test
    fun `reports a cycle with its path`() {
        val beans = listOf(bean("A", dep("B")), bean("B", dep("C")), bean("C", dep("A")))
        assertEquals(listOf("Dependency cycle: com.example.A -> com.example.B -> com.example.C -> com.example.A"), messages(beans))
    }

    @Test
    fun `reports a self dependency`() = assertEquals(listOf("Dependency cycle: com.example.A -> com.example.A"), messages(listOf(bean("A", dep("A")))))

    @Test
    fun `reports ambiguous providers with a hint`() {
        val beans = listOf(
            bean("Consumer", dep("Service")),
            bean("FirstImpl", provides = listOf("Service")),
            bean("SecondImpl", provides = listOf("Service")),
        )
        val problems = messages(beans)
        assertEquals(1, problems.size)
        assertContains(problems.single(), "Dependency com.example.Service of com.example.Consumer is ambiguous")
        assertContains(problems.single(), "\"firstImpl\" (com.example.FirstImpl)")
        assertContains(problems.single(), "Add @Qualifier at the injection point or mark one bean @Primary")
    }

    @Test
    fun `a single Primary candidate resolves the ambiguity`() {
        val beans = listOf(
            bean("Consumer", dep("Service")),
            bean("FirstImpl", provides = listOf("Service"), primary = true),
            bean("SecondImpl", provides = listOf("Service")),
        )
        assertEquals(emptyList(), messages(beans))

        val twoPrimaries = beans.map { if (it.type.simpleName == "SecondImpl") it.copy(primary = true) else it }
        assertContains(messages(twoPrimaries).single(), "several candidates are @Primary")
    }

    @Test
    fun `a Qualifier selects by name and follows the edge for cycle detection`() {
        val beans = listOf(
            bean("Consumer", dep("Service", "grumpy")),
            bean("FirstImpl", provides = listOf("Service")),
            bean("SecondImpl", dep("Consumer"), provides = listOf("Service"), name = "grumpy"),
        )
        assertEquals(listOf("Dependency cycle: com.example.Consumer -> com.example.SecondImpl -> com.example.Consumer"), messages(beans))
    }

    @Test
    fun `a Qualifier naming a bean of the wrong type is an error, an unknown name is not`() {
        val beans = listOf(bean("Consumer", dep("Service", "clock"), dep("Service", "elsewhere")), bean("Clock"))
        val problems = messages(beans)
        assertEquals(1, problems.size)
        assertContains(problems.single(), "Bean \"clock\" (com.example.Clock) does not provide com.example.Service, required by com.example.Consumer")
    }

    @Test
    fun `a Qualifier naming a duplicated name lists every bean of that name`() {
        val beans = listOf(bean("First", name = "shared"), bean("Second", name = "shared"), bean("Consumer", dep("Service", "shared")))
        val problems = messages(beans)
        assertContains(problems, "Bean \"shared\" (com.example.First, com.example.Second) does not provide com.example.Service, required by com.example.Consumer")
        assertTrue(problems.any { "Bean name \"shared\" is used by" in it })
    }

    @Test
    fun `reports duplicate names once per extra bean`() {
        val beans = listOf(bean("First", name = "shared"), bean("Second", name = "shared"), bean("Third", name = "shared"))
        val problems = messages(beans)
        assertEquals(2, problems.size)
        assertTrue(problems.all { "Bean name \"shared\" is used by" in it && "rename one with @Qualifier" in it })
    }

    @Test
    fun `the main class provides its types under the reserved name plugin`() {
        val plugin = mainPlugin("JavaPlugin")

        assertEquals(emptyList(), messages(listOf(bean("Consumer", dep("JavaPlugin"), dep("Main", "plugin"))), plugin = plugin))
        val notABean = plugin.copy(enhancements = emptyList())
        assertEquals(emptyList(), messages(listOf(bean("Other", name = "plugin")), plugin = notABean), "without @PluginBean the name is free")
        assertContains(messages(listOf(bean("Consumer", dep("Clock", "plugin"))), plugin = plugin).single(), "Bean \"plugin\" (com.example.Main) does not provide com.example.Clock")
        assertEquals(
            listOf("Bean name \"plugin\" is reserved for the plugin's main class; rename com.example.Other with @Qualifier"),
            messages(listOf(bean("Other", name = "plugin")), plugin = plugin),
        )
    }

    @Test
    fun `checks function parameters against the beans too`() {
        val beans = listOf(bean("FirstImpl", provides = listOf("Service")), bean("SecondImpl", provides = listOf("Service")))

        assertContains(messages(beans, listOf(function(dep("Service")))).single(), "of com.example.Main.start()")
        assertEquals(emptyList(), messages(beans, listOf(function(dep("Service", "secondImpl"), dep("Unknown")))))
    }

    @Test
    fun `checks the constructor parameters of other instantiated classes`() {
        val beans = listOf(bean("FirstImpl", provides = listOf("Service")), bean("SecondImpl", provides = listOf("Service")))

        assertContains(messages(beans, classes = listOf(listener("Ledger", dep("Service")))).single(), "Dependency com.example.Service of com.example.Ledger is ambiguous")
        assertEquals(emptyList(), messages(beans, classes = listOf(listener("Ledger", dep("Service", "firstImpl")))))
    }

    @Test
    fun `a prototype takes the plugin only as a type every plugin has`() {
        val prototype = listOf(Enhancement(BeanProcessors.EXPOSE_PROTOTYPE, Phase.PLUGIN_START_ONLY))
        val plugin = mainPlugin("VersatiaPlugin", "Plugin")
        val pluginTypes = setOf(type("VersatiaPlugin"), type("Plugin"))

        val strict = messages(listOf(bean("Session", dep("Main"), enhancements = prototype)), plugin = plugin, pluginTypes = pluginTypes)
        assertContains(strict.single(), "com.example.Session is a prototype, so its com.example.Main parameter receives the consuming plugin")
        assertEquals(emptyList(), messages(listOf(bean("Session", dep("VersatiaPlugin"), dep("Plugin"), enhancements = prototype)), plugin = plugin, pluginTypes = pluginTypes))
        assertEquals(emptyList(), messages(listOf(bean("Shared", dep("Main"))), plugin = plugin, pluginTypes = pluginTypes), "a local bean keeps the owner's instance")
    }

    @Test
    fun `a dependency no plugin can provide is an error with the reason`() {
        val unprovidable = { type: ClassName -> if (type.simpleName == "Clock") "Clock is declared in this plugin" else null }
        val beans = listOf(bean("Shop", dep("Clock"), dep("Economy")))

        assertEquals(listOf("Dependency com.example.Clock of com.example.Shop has no provider: Clock is declared in this plugin"), messages(beans, unprovidable = unprovidable))
        assertEquals(emptyList(), messages(beans + bean("Clock"), unprovidable = unprovidable), "a local provider is enough")
        assertContains(messages(emptyList(), listOf(function(dep("Clock"))), unprovidable = unprovidable).single(), "of com.example.Main.start() has no provider")
    }

    @Test
    fun `a dependent of a conditional bean carries the same condition`() {
        val economy = bean("Economy", onPlugins = listOf("Vault"))

        assertEquals(
            listOf("com.example.Shop depends on com.example.Economy, which exists only with @ConditionalOnPlugin(\"Vault\"); make com.example.Shop conditional on the same plugins or declare a provider without the condition"),
            messages(listOf(economy, bean("Shop", dep("Economy")))),
        )
        assertEquals(emptyList(), messages(listOf(economy, bean("Shop", dep("Economy"), onPlugins = listOf("Vault", "CommandAPI")))))
        assertContains(messages(listOf(economy), listOf(function(dep("Economy")))).single(), "com.example.Main.start() depends on com.example.Economy")
        assertEquals(emptyList(), messages(listOf(economy), listOf(function(dep("Economy"), classOnPlugins = listOf("Vault")))), "the declaring class's condition counts")
    }

    @Test
    fun `an unconditional provider stands in for a conditional one`() {
        val vault = bean("VaultEconomy", provides = listOf("Economy"), primary = true, onPlugins = listOf("Vault"))
        val fallback = bean("FreeEconomy", provides = listOf("Economy"))

        assertEquals(emptyList(), messages(listOf(vault, fallback, bean("Shop", dep("Economy")))))
        assertEquals(emptyList(), messages(listOf(vault, fallback), listOf(function(dep("Economy")))))
        assertEquals(emptyList(), messages(listOf(vault.copy(primary = false), fallback.copy(primary = true), bean("Shop", dep("Economy")))))
    }

    @Test
    fun `every provider conditional on plugins the dependent lacks is an error`() {
        val vault = bean("VaultEconomy", provides = listOf("Economy"), onPlugins = listOf("Vault"))
        val essentials = bean("EssentialsEconomy", provides = listOf("Economy"), primary = true, onPlugins = listOf("Essentials"))

        assertEquals(
            listOf(
                "com.example.Shop depends on com.example.Economy, whose providers all exist only with @ConditionalOnPlugin: " +
                    "com.example.VaultEconomy (\"Vault\"), com.example.EssentialsEconomy (\"Essentials\"); " +
                    "make com.example.Shop conditional on the same plugins or declare a provider without the condition",
            ),
            messages(listOf(vault, essentials, bean("Shop", dep("Economy")))),
        )
        assertEquals(
            listOf(
                "Dependency com.example.Economy of com.example.Shop is ambiguous while \"Vault\" is enabled; candidates: " +
                    "\"vaultEconomy\" (com.example.VaultEconomy), \"freeEconomy\" (com.example.FreeEconomy). Add @Qualifier at the injection point or mark one bean @Primary",
            ),
            messages(listOf(vault, bean("FreeEconomy", provides = listOf("Economy")), bean("Shop", dep("Economy")))),
            "a provider that may appear makes the choice ambiguous",
        )
        assertEquals(emptyList(), messages(listOf(vault, essentials, bean("Shop", dep("Economy"), onPlugins = listOf("Essentials")))), "Essentials is required, so its provider always exists")
    }

    @Test
    fun `an optional dependency may have no provider`() {
        val unprovidable = { _: ClassName -> "Clock is declared in this plugin" }
        val economy = bean("Economy", onPlugins = listOf("Vault"))

        assertEquals(emptyList(), messages(listOf(bean("Shop", dep("Clock", optional = true))), unprovidable = unprovidable))
        assertEquals(emptyList(), messages(listOf(economy, bean("Shop", dep("Economy", optional = true)))), "the default stands in while Vault is missing")
        assertEquals(emptyList(), messages(emptyList(), classes = listOf(listener("Greeter", dep("Clock", optional = true))), unprovidable = unprovidable))
        assertContains(
            messages(listOf(bean("A", provides = listOf("Clock")), bean("B", provides = listOf("Clock")), bean("Shop", dep("Clock", optional = true)))).single(),
            "is ambiguous",
        )
    }

    @Test
    fun `a supplied parameter is not a dependency`() {
        val count = ParameterDeclaration(ClassName("kotlin", "Int"), suppliedBy = "com.github.marcoral.versatia.config.ValueEnhancementProcessor")

        assertEquals(emptyList(), messages(listOf(bean("Shop", count)), unprovidable = { "never" }))
        assertEquals(emptyList(), messages(emptyList(), classes = listOf(listener("Greeter", count)), unprovidable = { "never" }))
    }
}
