package com.github.marcoral.versatia.ksp.model

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
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
    private val clock = ClassName("com.example", "Clock")

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

    private fun function(owner: String, name: String, kind: MemberOwner, reachable: Boolean, vararg parameters: ParameterDeclaration, jvmName: String = name) =
        FunctionDeclaration(
            ClassName("com.example", owner), kind, name, AnnotationRef(ClassName("com.github.marcoral.versatia.lifecycle", "AutoInvoke"), mapOf("phase" to "PLUGIN_START_ONLY")),
            listOf(Enhancement("com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor", EventType.PLUGIN_START_ONLY)),
            emptyList(), parameters.toList(), reachable, jvmName = jvmName,
        )

    private fun property(owner: String, name: String, kind: MemberOwner, type: TypeDeclaration, reachable: Boolean) = PropertyDeclaration(
        ClassName("com.example", owner), kind, name, AnnotationRef(ClassName("com.github.marcoral.versatia.config", "Value"), mapOf("key" to "shop.$name", "file" to "config.yml")),
        listOf(Enhancement("com.github.marcoral.versatia.config.ValueEnhancementProcessor", EventType.PLUGIN_RELOAD_OR_START)),
        emptyList(), type, reachable,
    )

    private fun plugin(vararg enhancements: EnhancedAnnotation) = PluginDeclaration(
        type = ClassName("com.example", "MyPlugin"),
        primary = false,
        annotations = emptyList(),
        assignableTypes = listOf(ClassName("com.example", "MyPlugin"), ClassName("org.bukkit.plugin.java", "JavaPlugin")),
        enhancements = enhancements.toList(),
    )

    /** The generated file with every run of whitespace collapsed, so that KotlinPoet's line breaks and indentation do not matter. */
    private fun code(model: DescriptorModel) = DescriptorGenerator.generate(model).toString().replace(Regex("\\s+"), " ")

    @Test
    fun `records where the producing annotation was written`() {
        val code = code(DescriptorModel("com.example", listOf(bean(clock))))

        assertContains(code, "origin = \"com.example.Clock\"")
    }

    @Test
    fun `generates an empty descriptor`() {
        val code = code(DescriptorModel("com.example", emptyList()))

        assertContains(code, "package com.example")
        assertContains(code, "object VersatiaDescriptor : VersatiaPluginDescriptor")
        assertContains(code, "override val formatMajor: Int = ${VersatiaPluginDescriptor.FORMAT_VERSION.major}")
        assertContains(code, "override val formatMinor: Int = ${VersatiaPluginDescriptor.FORMAT_VERSION.minor}")
        assertContains(code, "override val elements: List<EnhancedElement> get() = emptyList()")
    }

    @Test
    fun `calls the constructor directly for reachable classes`() {
        val bean = bean(
            ClassName("com.example", "ConsoleGreeter"),
            enhancements = listOf(Enhancement(BeanProcessors.EXPOSE_SINGLETON, EventType.PLUGIN_START_ONLY)),
            parameters = listOf(ParameterDeclaration(clock)),
            provides = listOf(ClassName("com.example", "Greeter")),
            primary = true,
            scope = exposeSingleton,
            annotations = listOf(primary),
        )

        val code = code(DescriptorModel("com.example", listOf(bean)))

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
        val bean = bean(ClassName("com.example", "Desk"), parameters = listOf(qualified(ClassName("com.example", "Greeter"), "grumpy")))

        val code = code(DescriptorModel("com.example", listOf(bean)))

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
        val bean = bean(clock, annotations = listOf(annotation))

        val code = code(DescriptorModel("com.example", listOf(bean)))

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
    fun `writes values Kotlin has no plain literal for`() {
        val annotation = AnnotationRef(
            ClassName("com.example", "Limits"),
            mapOf(
                "nan" to Double.NaN, "up" to Double.POSITIVE_INFINITY, "down" to Float.NEGATIVE_INFINITY, "floor" to Long.MIN_VALUE, "least" to Int.MIN_VALUE,
                "small" to 7.toByte(), "negative" to (-3).toShort(), "newline" to '\n', "quote" to '\'', "slash" to '\\',
            ),
        )

        val code = code(DescriptorModel("com.example", listOf(bean(clock, annotations = listOf(annotation)))))

        assertContains(code, "\"nan\" to Double.NaN")
        assertContains(code, "\"up\" to Double.POSITIVE_INFINITY")
        assertContains(code, "\"down\" to Float.NEGATIVE_INFINITY")
        assertContains(code, "\"floor\" to Long.MIN_VALUE")
        assertContains(code, "\"least\" to Int.MIN_VALUE")
        assertContains(code, "\"small\" to 7.toByte()")
        assertContains(code, "\"negative\" to (-3).toShort()")
        assertContains(code, "\"newline\" to '\\u000a'")
        assertContains(code, "\"quote\" to '\\''")
        assertContains(code, "\"slash\" to '\\\\'")
    }

    @Test
    fun `uses a parameterless lambda for beans without dependencies`() {
        val code = code(DescriptorModel("com.example", listOf(bean(clock))))

        assertContains(code, "constructorParameters = emptyList()")
        assertContains(code, "instantiator = Instantiator.Direct { _ -> Clock() }")
    }

    @Test
    fun `falls back to reflection for private classes, with JVM parameter names`() {
        val hidden = ClassName("com.example", "Outer", "Hidden")
        val parameters = listOf(ParameterDeclaration(clock), ParameterDeclaration(ClassName("kotlin", "Any"), jvmType = "java.lang.Object"))
        val bean = bean(hidden, parameters = parameters, reachable = false)

        val code = code(DescriptorModel("com.example", listOf(bean)))

        assertContains(code, "className = \"com.example.Outer.Hidden\"")
        assertContains(code, "instantiator = Instantiator.Reflective(\"com.example.Outer\${'$'}Hidden\", listOf(\"com.example.Clock\", \"java.lang.Object\"))")
    }

    @Test
    fun `emits one element per enhancement of a class`() {
        val enhancements = listOf(
            Enhancement(BeanProcessors.LOCAL, EventType.PLUGIN_START_ONLY),
            Enhancement("com.example.AuditEnhancementProcessor", EventType.PLUGIN_STOP),
        )
        val code = code(DescriptorModel("com.example", listOf(bean(clock, enhancements))))

        assertEquals(2, Regex("EnhancedElement\\(").findAll(code).count())
        assertContains(code, "processorClass = \"com.example.AuditEnhancementProcessor\"")
        assertContains(code, "phase = EventType.PLUGIN_STOP")
    }

    @Test
    fun `emits function targets with direct or reflective invokers`() {
        val model = DescriptorModel(
            "com.example",
            emptyList(),
            listOf(
                function("Main", "start", MemberOwner.PLUGIN, reachable = true, qualified(clock, "wall")),
                function("Registry", "refresh", MemberOwner.OBJECT, reachable = true),
                function("Main", "secret", MemberOwner.PLUGIN, reachable = false, ParameterDeclaration(clock)),
                function("Main", "hidden", MemberOwner.PLUGIN, reachable = false, ParameterDeclaration(ClassName("kotlin", "String"), jvmType = "java.lang.String"), jvmName = "hidden\$alpha"),
            ),
        )

        val code = code(model)

        assertContains(code, "processorClass = \"com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor\"")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.lifecycle.AutoInvoke\", mapOf(\"phase\" to \"PLUGIN_START_ONLY\"))")
        assertContains(code, "functionName = \"start\"")
        assertContains(code, "owner = MemberOwner.PLUGIN")
        assertContains(code, "parameters = listOf(Parameter(\"com.example.Clock\", listOf(AnnotationOccurrence(\"com.github.marcoral.versatia.di.Qualifier\", mapOf(\"value\" to \"wall\")))))")
        assertContains(code, "invoker = Invoker.Direct { instance, args -> (instance as Main).start(args[0] as Clock) }")
        assertContains(code, "invoker = Invoker.Direct { _, _ -> Registry.refresh() }")
        assertContains(code, "invoker = Invoker.Reflective(\"com.example.Main\", \"secret\", listOf(\"com.example.Clock\"))")
        assertTrue("invoker = Invoker.Reflective(\"com.example.Main\", \"hidden\${'$'}alpha\", listOf(\"java.lang.String\"))" in code, "the JVM names, not the Kotlin ones")
    }

    @Test
    fun `emits one element per enhancement of the main class, sharing one target`() {
        val plugin = plugin(
            EnhancedAnnotation(AnnotationRef(ClassName("com.github.marcoral.versatia.di", "PluginBean")), listOf(Enhancement(BeanProcessors.PLUGIN, EventType.PLUGIN_START_ONLY)), origin = ClassName("com.github.marcoral.versatia", "VersatiaPlugin")),
            EnhancedAnnotation(AnnotationRef(ClassName("com.example", "Audited")), listOf(Enhancement("com.example.AuditEnhancementProcessor", EventType.PLUGIN_STOP)), origin = ClassName("com.example", "MyPlugin")),
        )

        val code = code(DescriptorModel("com.example", listOf(bean(clock)), plugin = plugin))

        assertContains(code, "processorClass = \"com.github.marcoral.versatia.di.PluginBeanEnhancementProcessor\"")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.di.PluginBean\")")
        assertContains(code, "origin = \"com.github.marcoral.versatia.VersatiaPlugin\"")
        assertContains(code, "processorClass = \"com.example.AuditEnhancementProcessor\"")
        assertContains(code, "phase = EventType.PLUGIN_STOP")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.example.Audited\")")
        assertEquals(2, Regex("target = pluginTarget\\(\\)").findAll(code).count())
        assertEquals(1, Regex("PluginTarget\\(").findAll(code).count(), "the target is written once")
        assertContains(code, "private fun pluginTarget(): PluginTarget = PluginTarget( className = \"com.example.MyPlugin\",")
        assertContains(code, "assignableTypes = listOf(\"com.example.MyPlugin\", \"org.bukkit.plugin.java.JavaPlugin\")")
        assertFalse("name =" in code, "the plugin bean has no name of its own")
        assertTrue(code.indexOf("target = pluginTarget()") < code.indexOf("ClassTarget("), "the plugin comes first")
    }

    @Test
    fun `keeps the plugin package`() {
        val file = DescriptorGenerator.generate(DescriptorModel("com.example.alpha", emptyList()))
        assertEquals("com.example.alpha", file.packageName)
        assertEquals("VersatiaDescriptor", file.name)
    }

    @Test
    fun `emits subclass elements with their origin`() {
        val audit = AnnotationRef(ClassName("com.example", "AuditSubclasses"))
        val enhancement = Enhancement("com.example.AuditEnhancementProcessor", EventType.PLUGIN_START_ONLY)
        val origin = ClassName("com.example", "AuditedPlugin")
        val subclasses = listOf(
            SubclassElement(origin, audit, enhancement, null),
            SubclassElement(origin, audit, enhancement, ClassDeclaration(ClassName("com.example", "Ledger"), emptyList(), listOf(ParameterDeclaration(clock)), listOf(ClassName("com.example", "Ledger")), reachable = true)),
            SubclassElement(origin, audit, enhancement, ClassDeclaration(ClassName("com.example", "Console"), emptyList(), emptyList(), listOf(ClassName("com.example", "Console")), reachable = true, isObject = true)),
            SubclassElement(origin, audit, enhancement, ClassDeclaration(ClassName("com.example", "Vault"), emptyList(), emptyList(), listOf(ClassName("com.example", "Vault")), reachable = false, isObject = true)),
        )

        val code = code(DescriptorModel("com.example", emptyList(), plugin = plugin(), subclasses = subclasses))

        assertEquals(4, code.split("origin = \"com.example.AuditedPlugin\"").size - 1)
        assertContains(code, "target = pluginTarget()")
        assertContains(code, "private fun pluginTarget(): PluginTarget")
        assertContains(code, "instantiator = Instantiator.Direct { args -> Ledger(args[0] as Clock) }")
        assertContains(code, "instantiator = Instantiator.Direct { _ -> Console }")
        assertContains(code, "instantiator = Instantiator.ObjectInstance(\"com.example.Vault\")")
    }

    @Test
    fun `emits property targets with direct or reflective setters`() {
        val int = ClassName("kotlin", "Int")
        val model = DescriptorModel(
            "com.example",
            emptyList(),
            properties = listOf(
                property("Main", "motd", MemberOwner.PLUGIN, TypeDeclaration(ClassName("kotlin", "String")), reachable = true),
                property("Limits", "slots", MemberOwner.OBJECT, TypeDeclaration(ClassName("kotlin.collections", "List"), listOf(TypeDeclaration(int)), nullable = true), reachable = true),
                property("Shop", "tax", MemberOwner.INSTANCE, TypeDeclaration(int), reachable = false),
            ),
        )

        val code = code(model)

        assertTrue(code.indexOf("@Suppress(\"UNCHECKED_CAST\")") in 0..<code.indexOf("object VersatiaDescriptor"), "the object suppresses the unchecked casts")
        assertContains(code, "processorClass = \"com.github.marcoral.versatia.config.ValueEnhancementProcessor\"")
        assertContains(code, "annotation = AnnotationOccurrence(\"com.github.marcoral.versatia.config.Value\", mapOf(\"key\" to \"shop.motd\", \"file\" to \"config.yml\"))")
        assertContains(code, "propertyName = \"motd\"")
        assertContains(code, "owner = MemberOwner.PLUGIN")
        assertContains(code, "type = TypeReference(\"kotlin.String\")")
        assertContains(code, "setter = Setter.Direct { instance, value -> (instance as Main).motd = value as String }")
        assertContains(code, "type = TypeReference(\"kotlin.collections.List\", listOf(TypeReference(\"kotlin.Int\")), true)")
        assertContains(code, "setter = Setter.Direct { _, value -> Limits.slots = value as List<Int>? }")
        assertContains(code, "setter = Setter.Reflective(\"com.example.Shop\", \"tax\")")
    }

    @Test
    fun `lists properties before functions`() {
        val property = PropertyDeclaration(
            ClassName("com.example", "Main"), MemberOwner.PLUGIN, "motd", AnnotationRef(ClassName("com.github.marcoral.versatia.config", "Value")),
            listOf(Enhancement("com.example.P", EventType.PLUGIN_RELOAD_OR_START)), emptyList(), TypeDeclaration(ClassName("kotlin", "String")), reachable = true,
        )

        val code = code(DescriptorModel("com.example", emptyList(), functions = listOf(function("Main", "start", MemberOwner.PLUGIN, reachable = true)), properties = listOf(property)))

        assertTrue(code.indexOf("PropertyTarget(") < code.indexOf("FunctionTarget("), "values are set before functions run")
        assertFalse("@Suppress" in code, "no unchecked cast without a generic property")
    }

    @Test
    fun `splits many elements into private functions`() {
        val beans = (1..120).map { bean(ClassName("com.example", "Bean$it")) }

        val code = code(DescriptorModel("com.example", beans))

        assertContains(code, "get() = elements1() + elements2() + elements3()")
        assertContains(code, "private fun elements1(): List<EnhancedElement> = listOf(")
        assertFalse("elements4" in code)
        assertEquals(120, Regex("EnhancedElement\\(").findAll(code).count())
    }

    @Test
    fun `aliases the imported types a class of the plugin's package shares a name with`() {
        val map = TypeDeclaration(ClassName("kotlin.collections", "Map"), listOf(TypeDeclaration(ClassName("kotlin", "String")), TypeDeclaration(ClassName("kotlin", "Int"))))
        val game = bean(ClassName("com.example", "Game"), parameters = listOf(ParameterDeclaration(ClassName("com.example", "Map")), ParameterDeclaration(ClassName("com.example.other", "Clock"))))
        val model = DescriptorModel(
            "com.example",
            listOf(game),
            properties = listOf(property("Main", "prices", MemberOwner.PLUGIN, map, reachable = true)),
            shadowedNames = setOf("Map", "List", "Suppress", "Clock", "Game", "Main"),
        )

        val code = code(model)

        assertContains(code, "import kotlin.collections.Map as CollectionsMap")
        assertContains(code, "import kotlin.collections.List as CollectionsList")
        assertContains(code, "import kotlin.Suppress as KotlinSuppress")
        assertContains(code, "import com.example.other.Clock as OtherClock")
        assertContains(code, "value as CollectionsMap<String, Int>")
        assertTrue("Game(args[0] as Map, args[1] as OtherClock)" in code, "the package's own Map stays unqualified")
        assertFalse(" as Collections" in code(model.copy(shadowedNames = setOf("Game", "Main"))), "nothing to alias when no name is shared")
    }
}
