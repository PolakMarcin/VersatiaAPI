package com.github.marcoral.versatia.ksp.di

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.ksp.model.AnnotationRef
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
    private val local = listOf(Enhancement("com.github.marcoral.versatia.di.LocalBeanEnhancementProcessor", EventType.PLUGIN_START_ONLY))

    private fun type(name: String) = ClassName("com.example", name)

    private fun bean(
        className: String,
        vararg parameters: ParameterDeclaration,
        provides: List<String> = emptyList(),
        name: String = className.replaceFirstChar { it.lowercaseChar() },
        primary: Boolean = false,
    ) = BeanDeclaration(
        type = type(className),
        name = name,
        primary = primary,
        scope = localBean,
        enhancements = local,
        annotations = emptyList(),
        parameters = parameters.toList(),
        assignableTypes = listOf(type(className)) + provides.map(::type),
        reachable = true,
    )

    private fun dep(typeName: String, qualifier: String? = null) = ParameterDeclaration(
        type(typeName),
        listOfNotNull(qualifier?.let { AnnotationRef(QUALIFIER, mapOf("value" to it)) }),
    )

    private fun function(vararg parameters: ParameterDeclaration) = FunctionDeclaration(
        owner = type("Main"),
        ownerKind = FunctionOwner.PLUGIN,
        name = "start",
        annotation = AnnotationRef(ClassName("com.github.marcoral.versatia.lifecycle", "AutoInvoke")),
        enhancements = listOf(Enhancement("com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor", EventType.PLUGIN_START_ONLY)),
        annotations = emptyList(),
        parameters = parameters.toList(),
        reachable = true,
    )

    private fun messages(beans: List<BeanDeclaration>, functions: List<FunctionDeclaration> = emptyList(), plugin: PluginDeclaration? = null) =
        BeanGraphValidator.validate(beans, functions, plugin).map { it.message }

    @Test
    fun `accepts an acyclic graph with unresolved dependencies`() {
        val beans = listOf(bean("A", dep("B")), bean("B", dep("C")), bean("C", dep("FromOtherPlugin")))
        assertEquals(emptyList(), messages(beans))
    }

    @Test
    fun `reports a cycle with its path`() {
        val beans = listOf(bean("A", dep("B")), bean("B", dep("C")), bean("C", dep("A")))
        assertEquals(listOf("Dependency cycle: A -> B -> C -> A"), messages(beans))
    }

    @Test
    fun `reports a self dependency`() = assertEquals(listOf("Dependency cycle: A -> A"), messages(listOf(bean("A", dep("A")))))

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
        assertEquals(listOf("Dependency cycle: Consumer -> SecondImpl -> Consumer"), messages(beans))
    }

    @Test
    fun `a Qualifier naming a bean of the wrong type is an error, an unknown name is not`() {
        val beans = listOf(bean("Consumer", dep("Service", "clock"), dep("Service", "elsewhere")), bean("Clock"))
        val problems = messages(beans)
        assertEquals(1, problems.size)
        assertContains(problems.single(), "Bean \"clock\" (com.example.Clock) does not provide com.example.Service, required by com.example.Consumer")
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
        val plugin = PluginDeclaration(type("Main"), primary = false, annotations = emptyList(), assignableTypes = listOf(type("Main"), type("JavaPlugin")))

        assertEquals(emptyList(), messages(listOf(bean("Consumer", dep("JavaPlugin"), dep("Main", "plugin"))), plugin = plugin))
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
}
