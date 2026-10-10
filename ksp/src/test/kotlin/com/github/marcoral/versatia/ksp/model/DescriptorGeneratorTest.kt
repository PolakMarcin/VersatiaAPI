package com.github.marcoral.versatia.ksp.model

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.ksp.di.BeanDeclaration
import com.github.marcoral.versatia.ksp.di.PluginDeclaration
import com.github.marcoral.versatia.ksp.di.QUALIFIER
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptorGeneratorTest {
    private val exposeSingleton = AnnotationRef(ClassName("com.github.marcoral.versatia.di", "ExposeSingleton"))
    private val localBean = AnnotationRef(ClassName("com.github.marcoral.versatia.di", "LocalBean"))
    private val primary = AnnotationRef(ClassName("com.github.marcoral.versatia.di", "Primary"))
    private val local = listOf(Enhancement(BeanProcessors.LOCAL, EventType.PLUGIN_START_ONLY))

    private fun bean(
        type: ClassName,
        enhancements: List<Enhancement> = local,
        parameters: List<ParameterDeclaration> = emptyList(),
        provides: List<ClassName> = emptyList(),
        reachable: Boolean = true,
        primary: Boolean = false,
        scope: AnnotationRef = localBean,
        annotations: List<AnnotationRef> = emptyList(),
    ) = BeanDeclaration(
        type = type,
        name = type.simpleName.replaceFirstChar { it.lowercaseChar() },
        primary = primary,
        scope = scope,
        enhancements = enhancements,
        annotations = annotations,
        parameters = parameters,
        assignableTypes = listOf(type) + provides,
        reachable = reachable,
    )

    private fun qualified(type: ClassName, name: String) = ParameterDeclaration(type, listOf(AnnotationRef(QUALIFIER, mapOf("value" to name))))

    @Test
    fun `generates an empty descriptor`() {
        val code = DescriptorGenerator.generate(DescriptorModel("com.example", emptyList())).toString()

        assertContains(code, "package com.example")
        assertContains(code, "object VersatiaDescriptor : VersatiaPluginDescriptor")
        assertContains(code, "override val formatMajor: Int = ${VersatiaPluginDescriptor.FORMAT_VERSION.major}")
        assertContains(code, "override val formatMinor: Int = ${VersatiaPluginDescriptor.FORMAT_VERSION.minor}")
        assertContains(code, "override val elements: List<EnhancedElement>\n    get() = emptyList()")
    }

    @Test
    fun `calls the constructor directly for reachable classes`() {
        val clock = ClassName("com.example", "Clock")
        val greeter = ClassName("com.example", "Greeter")
        val bean = bean(
            ClassName("com.example", "ConsoleGreeter"),
            enhancements = listOf(Enhancement(BeanProcessors.EXPOSE_SINGLETON, EventType.PLUGIN_START_ONLY)),
            parameters = listOf(ParameterDeclaration(clock)),
            provides = listOf(greeter),
            primary = true,
            scope = exposeSingleton,
            annotations = listOf(primary),
        )

        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean))).toString()

        assertContains(code, "processorClass = \"com.github.marcoral.versatia.di.ExposeSingletonEnhancementProcessor\"")
        assertContains(code, "phase = EventType.PLUGIN_START_ONLY")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.di.ExposeSingleton\")")
        assertContains(code, "className = \"com.example.ConsoleGreeter\"")
        assertContains(code, "assignableTypes = listOf(\"com.example.ConsoleGreeter\", \"com.example.Greeter\")")
        assertContains(code, "annotations = listOf(AnnotationOccurrence(\"com.github.marcoral.versatia.di.Primary\"))")
        assertContains(code, "constructorParameters = listOf(Parameter(\"com.example.Clock\"))")
        assertContains(code, "instantiator = Instantiator.Direct { args -> ConsoleGreeter(args[0] as Clock) }")
    }

    @Test
    fun `records attributes on parameters with their arguments`() {
        val greeter = ClassName("com.example", "Greeter")
        val bean = bean(ClassName("com.example", "Desk"), parameters = listOf(qualified(greeter, "grumpy")))

        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean))).toString()

        assertContains(
            code,
            "constructorParameters = listOf(Parameter(\"com.example.Greeter\", listOf(AnnotationOccurrence(\"com.github.marcoral.versatia.di.Qualifier\", mapOf(\"value\" to \"grumpy\")))))",
        )
        assertContains(code, "instantiator = Instantiator.Direct { args -> Desk(args[0] as Greeter) }")
    }

    @Test
    fun `writes every supported argument value as a literal`() {
        val annotation = AnnotationRef(
            ClassName("com.example", "Settings"),
            mapOf("text" to "a\"b", "count" to 3, "big" to 4L, "ratio" to 1.5, "flag" to true, "letter" to 'x', "tags" to listOf("p", "q"), "none" to null),
        )
        val bean = bean(ClassName("com.example", "Clock"), annotations = listOf(annotation))

        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean))).toString()

        assertContains(code, "\"text\" to \"a\\\"b\"")
        assertContains(code, "\"count\" to 3")
        assertContains(code, "\"big\" to 4L")
        assertContains(code, "\"ratio\" to 1.5")
        assertContains(code, "\"flag\" to true")
        assertContains(code, "\"letter\" to 'x'")
        assertContains(code, "\"tags\" to listOf(\"p\", \"q\")")
        assertContains(code, "\"none\" to null")
    }

    @Test
    fun `uses a parameterless lambda for beans without dependencies`() {
        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean(ClassName("com.example", "Clock"))))).toString()

        assertContains(code, "constructorParameters = emptyList()")
        assertContains(code, "instantiator = Instantiator.Direct { _ -> Clock() }")
    }

    @Test
    fun `falls back to reflection for private classes`() {
        val hidden = ClassName("com.example", "Outer", "Hidden")
        val bean = bean(hidden, parameters = listOf(ParameterDeclaration(ClassName("com.example", "Clock"))), reachable = false)

        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean))).toString()

        assertContains(code, "className = \"com.example.Outer.Hidden\"")
        assertContains(code, "instantiator = Instantiator.Reflective(\"com.example.Outer\${'\$'}Hidden\", listOf(\"com.example.Clock\"))")
    }

    @Test
    fun `emits one element per enhancement of a class`() {
        val enhancements = listOf(
            Enhancement(BeanProcessors.LOCAL, EventType.PLUGIN_START_ONLY),
            Enhancement("com.example.AuditEnhancementProcessor", EventType.PLUGIN_STOP),
        )
        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean(ClassName("com.example", "Clock"), enhancements)))).toString()

        assertEquals(2, Regex("EnhancedElement\\(").findAll(code).count())
        assertContains(code, "processorClass = \"com.example.AuditEnhancementProcessor\"")
        assertContains(code, "phase = EventType.PLUGIN_STOP")
    }

    @Test
    fun `emits function targets with direct or reflective invokers`() {
        val autoInvoke = ClassName("com.github.marcoral.versatia.lifecycle", "AutoInvoke")
        val clock = ClassName("com.example", "Clock")
        fun function(owner: String, name: String, kind: FunctionOwner, reachable: Boolean, vararg parameters: ParameterDeclaration) = FunctionDeclaration(
            ClassName("com.example", owner), kind, name, AnnotationRef(autoInvoke, mapOf("phase" to "PLUGIN_START_ONLY")),
            listOf(Enhancement("com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor", EventType.PLUGIN_START_ONLY)),
            emptyList(), parameters.toList(), reachable,
        )
        val model = DescriptorModel(
            "com.example",
            emptyList(),
            listOf(
                function("Main", "start", FunctionOwner.PLUGIN, reachable = true, qualified(clock, "wall")),
                function("Registry", "refresh", FunctionOwner.OBJECT, reachable = true),
                function("Main", "secret", FunctionOwner.PLUGIN, reachable = false, ParameterDeclaration(clock)),
            ),
        )

        val code = DescriptorGenerator.generate(model).toString()

        assertContains(code, "processorClass = \"com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor\"")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.lifecycle.AutoInvoke\", mapOf(\"phase\" to \"PLUGIN_START_ONLY\"))")
        assertContains(code, "functionName = \"start\"")
        assertContains(code, "owner = FunctionOwner.PLUGIN")
        assertContains(code, "parameters = listOf(Parameter(\"com.example.Clock\", listOf(AnnotationOccurrence(\"com.github.marcoral.versatia.di.Qualifier\", mapOf(\"value\" to \"wall\")))))")
        assertContains(code, "invoker = Invoker.Direct { instance, args -> (instance as Main).start(args[0] as Clock) }")
        assertContains(code, "invoker = Invoker.Direct { _, _ -> Registry.refresh() }")
        assertContains(code, "invoker = Invoker.Reflective(\"com.example.Main\", \"secret\", listOf(\"com.example.Clock\"))")
    }

    @Test
    fun `emits one element per enhancement of the main class`() {
        val plugin = PluginDeclaration(
            type = ClassName("com.example", "MyPlugin"),
            primary = false,
            annotations = emptyList(),
            assignableTypes = listOf(ClassName("com.example", "MyPlugin"), ClassName("org.bukkit.plugin.java", "JavaPlugin")),
            enhancements = listOf(
                EnhancedAnnotation(AnnotationRef(ClassName("com.github.marcoral.versatia.di", "PluginBean")), listOf(Enhancement(BeanProcessors.PLUGIN, EventType.PLUGIN_START_ONLY))),
                EnhancedAnnotation(AnnotationRef(ClassName("com.example", "Audited")), listOf(Enhancement("com.example.AuditEnhancementProcessor", EventType.PLUGIN_STOP))),
            ),
        )

        val code = DescriptorGenerator.generate(DescriptorModel("com.example", listOf(bean(ClassName("com.example", "Clock"))), plugin = plugin)).toString()

        assertContains(code, "processorClass = \"com.github.marcoral.versatia.di.PluginBeanEnhancementProcessor\"")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.di.PluginBean\")")
        assertContains(code, "processorClass = \"com.example.AuditEnhancementProcessor\"")
        assertContains(code, "phase = EventType.PLUGIN_STOP")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.example.Audited\")")
        assertEquals(2, Regex("target = PluginTarget\\(").findAll(code).count())
        assertContains(code, "className = \"com.example.MyPlugin\"")
        assertContains(code, "assignableTypes = listOf(\"com.example.MyPlugin\", \"org.bukkit.plugin.java.JavaPlugin\")")
        assertFalse("name =" in code, "the plugin bean has no name of its own")
        assertTrue(code.indexOf("PluginTarget(") < code.indexOf("ClassTarget("), "the plugin comes first")
    }

    @Test
    fun `keeps the plugin package`() {
        val file = DescriptorGenerator.generate(DescriptorModel("com.example.alpha", emptyList()))
        assertEquals("com.example.alpha", file.packageName)
        assertEquals("VersatiaDescriptor", file.name)
    }
}
