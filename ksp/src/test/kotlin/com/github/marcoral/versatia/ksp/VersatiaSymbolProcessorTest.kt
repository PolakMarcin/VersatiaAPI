package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
import com.github.marcoral.versatia.resources.ExtractResources
import com.github.marcoral.versatia.resources.ResourceProcessors
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFalse

/** Compiles sample plugin sources with the processor; see docs/enhancements.md. */
@OptIn(ExperimentalCompilerApi::class)
class VersatiaSymbolProcessorTest {
    @TempDir
    lateinit var tempDir: File

    private val qualifier = "com.github.marcoral.versatia.di.Qualifier"
    private val primary = "com.github.marcoral.versatia.di.Primary"

    private val alphaPlugin = SourceFile.kotlin(
        "AlphaPlugin.kt",
        """
        package com.example.alpha

        import com.github.marcoral.versatia.VersatiaPlugin

        class AlphaPlugin : VersatiaPlugin()
        """,
    )

    /** With [mainClass] set, plugin.yml names it and the class is added to the sources. */
    private fun compile(
        vararg sources: SourceFile,
        mainClass: String? = "com.example.alpha.AlphaPlugin",
    ): JvmCompilationResult = KotlinCompilation().apply {
        this.sources = sources.toList() + listOfNotNull(alphaPlugin.takeIf { mainClass == "com.example.alpha.AlphaPlugin" })
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = System.out
        configureKsp {
            symbolProcessorProviders += VersatiaSymbolProcessorProvider()
            withCompilation = true
            if (mainClass != null) {
                val pluginYml = File(tempDir, "plugin.yml").apply { writeText("name: Alpha\nmain: $mainClass\n") }
                processorOptions[VersatiaSymbolProcessor.OPTION_PLUGIN_YML] = pluginYml.path
            }
        }
    }.compile()

    private fun JvmCompilationResult.descriptor(packageName: String = "com.example.alpha") =
        classLoader.loadClass("$packageName.${VersatiaPluginDescriptor.CLASS_SIMPLE_NAME}").getDeclaredField("INSTANCE").get(null) as VersatiaPluginDescriptor

    private fun VersatiaPluginDescriptor.classTargets() = elements.mapNotNull { it.target as? ClassTarget }.associateBy { it.className }

    @Test
    fun `generates a descriptor next to the main class`() {
        val result = compile(
            SourceFile.kotlin(
                "Beans.kt",
                """
                package com.example.alpha.service

                import com.github.marcoral.versatia.di.ExposePrototype
                import com.github.marcoral.versatia.di.ExposeSingleton
                import com.github.marcoral.versatia.di.LocalBean

                interface Greeter

                @ExposeSingleton
                class Clock

                @ExposePrototype
                class Ticket(val clock: Clock)

                @LocalBean
                internal class ConsoleGreeter(val clock: Clock, val ticket: Ticket) : Greeter

                @LocalBean
                private class HiddenAudit(val greeter: Greeter)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val generated = result.sourcesGeneratedBySymbolProcessor.single()
        assertEquals("VersatiaDescriptor.kt", generated.name)
        assertContains(generated.readText(), "package com.example.alpha")

        val descriptor = result.descriptor()
        assertEquals(VersatiaPluginDescriptor.FORMAT_VERSION, descriptor.formatVersion)
        val byClass = descriptor.classTargets()

        assertEquals(
            mapOf(
                "com.example.alpha.service.Clock" to BeanProcessors.EXPOSE_SINGLETON,
                "com.example.alpha.service.Ticket" to BeanProcessors.EXPOSE_PROTOTYPE,
                "com.example.alpha.service.ConsoleGreeter" to BeanProcessors.LOCAL,
                "com.example.alpha.service.HiddenAudit" to BeanProcessors.LOCAL,
            ),
            descriptor.elements.filter { it.target is ClassTarget }.associate { (it.target as ClassTarget).className to it.processorClass },
        )
        assertEquals(
            AnnotationOccurrence("com.github.marcoral.versatia.di.LocalBean"),
            descriptor.elements.single { (it.target as? ClassTarget)?.className == "com.example.alpha.service.ConsoleGreeter" }.annotation,
        )

        val greeter = byClass.getValue("com.example.alpha.service.ConsoleGreeter")
        assertEquals(listOf("com.example.alpha.service.ConsoleGreeter", "com.example.alpha.service.Greeter"), greeter.assignableTypes)
        assertEquals(listOf(Parameter("com.example.alpha.service.Clock"), Parameter("com.example.alpha.service.Ticket")), greeter.constructorParameters)
        assertEquals(emptyList(), greeter.annotations)
        assertIs<Instantiator.Direct>(greeter.instantiator)

        val hidden = byClass.getValue("com.example.alpha.service.HiddenAudit")
        assertEquals(
            Instantiator.Reflective("com.example.alpha.service.HiddenAudit", listOf("com.example.alpha.service.Greeter")),
            hidden.instantiator,
        )

        val clock = byClass.getValue("com.example.alpha.service.Clock").instantiator.create(result.classLoader, emptyList())
        val ticket = byClass.getValue("com.example.alpha.service.Ticket").instantiator.create(result.classLoader, listOf(clock))
        val greeterInstance = greeter.instantiator.create(result.classLoader, listOf(clock, ticket))
        val audit = hidden.instantiator.create(result.classLoader, listOf(greeterInstance))
        assertEquals("com.example.alpha.service.HiddenAudit", audit.javaClass.name)
    }

    @Test
    fun `recognises scopes that inherit Bean transitively`() {
        val result = compile(
            SourceFile.kotlin(
                "Custom.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @LocalBean
                annotation class Service

                @Service
                class Inventory
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val element = result.descriptor().elements.single { it.target is ClassTarget }
        assertEquals(BeanProcessors.LOCAL, element.processorClass)
        assertEquals(EventType.PLUGIN_START_ONLY, element.phase)
        assertEquals(AnnotationOccurrence("com.example.alpha.Service"), element.annotation)
    }

    @Test
    fun `reads the phase from the property named by enhancementPhaseParamRefName`() {
        val result = compile(
            SourceFile.kotlin(
                "Phased.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean
                import com.github.marcoral.versatia.ksp.testscopes.PhasedBean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @Bean
                @VersatiaEnhancement("com.example.RunAtEnhancementProcessor", enhancementPhaseParamRefName = "at")
                annotation class RunAt(val at: EventType, val note: String = "")

                @RunAt(EventType.PLUGIN_STOP, note = "late")
                class FromSource

                @PhasedBean(EventType.PLUGIN_RELOAD_ONLY)
                class FromClasspath
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements.filter { it.target is ClassTarget }.associateBy { (it.target as ClassTarget).className }
        assertEquals(EventType.PLUGIN_STOP, elements.getValue("com.example.alpha.FromSource").phase)
        assertEquals(EventType.PLUGIN_RELOAD_ONLY, elements.getValue("com.example.alpha.FromClasspath").phase)
        assertEquals(
            AnnotationOccurrence("com.example.alpha.RunAt", mapOf("at" to "PLUGIN_STOP", "note" to "late")),
            elements.getValue("com.example.alpha.FromSource").annotation,
            "the producing annotation is recorded with all its arguments",
        )
    }

    @Test
    fun `emits one element per repeated VersatiaEnhancement, also from compiled scopes`() {
        val result = compile(
            SourceFile.kotlin(
                "Audited.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ksp.testscopes.AuditedBean

                @AuditedBean
                class Ledger
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements.filter { it.target is ClassTarget }
        assertEquals(
            setOf(BeanProcessors.LOCAL to EventType.PLUGIN_START_ONLY, "com.example.test.AuditEnhancementProcessor" to EventType.PLUGIN_STOP),
            elements.map { it.processorClass to it.phase }.toSet(),
        )
        assertEquals(2, elements.size)
        assertTrue(elements.all { (it.target as ClassTarget).className == "com.example.alpha.Ledger" })
    }

    @Test
    fun `rejects enhancements whose phase is not declared exactly once`() {
        val result = compile(
            SourceFile.kotlin(
                "Scopes.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P")
                annotation class NoPhase

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhase = EventType.PLUGIN_START_ONLY, enhancementPhaseParamRefName = "at")
                annotation class BothPhases(val at: EventType)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhaseParamRefName = "missing")
                annotation class MissingProperty(val at: EventType)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhaseParamRefName = "at")
                annotation class WrongType(val at: String)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("", enhancementPhase = EventType.PLUGIN_START_ONLY)
                annotation class NoProcessor

                @NoPhase class A
                @BothPhases(EventType.PLUGIN_STOP) class B
                @MissingProperty(EventType.PLUGIN_STOP) class C
                @WrongType("x") class D
                @NoProcessor class E
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "on NoPhase declares no phase; set enhancementPhase or enhancementPhaseParamRefName")
        assertContains(result.messages, "on BothPhases sets both enhancementPhase and enhancementPhaseParamRefName")
        assertContains(result.messages, "on MissingProperty refers to property \"missing\", which MissingProperty does not declare")
        assertContains(result.messages, "Property \"at\" of WrongType must be of type EventType")
        assertContains(result.messages, "@VersatiaEnhancement on NoProcessor has no processorClass")
        assertContains(result.messages, "Scopes.kt:")
    }

    @Test
    fun `rejects a bean scope that does not run at plugin start`() {
        val result = compile(
            SourceFile.kotlin(
                "Late.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean
                import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @Bean
                @VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = EventType.PLUGIN_STOP)
                annotation class LateBean

                @LateBean
                class Straggler
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "LateBean runs ${BeanProcessors.LOCAL} at PLUGIN_STOP; beans can only be declared at PLUGIN_START_ONLY")
        assertContains(result.messages, "Late.kt:")
    }

    @Test
    fun `describes AutoInvoke functions of the plugin, objects and beans`() {
        val result = compile(
            SourceFile.kotlin(
                "Invoked.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @LocalBean
                class Clock

                class AlphaPlugin : VersatiaPlugin() {
                    @AutoInvoke(EventType.PLUGIN_RELOAD_OR_START)
                    fun announce() {}

                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    private fun warmUp(clock: Clock) {}
                }

                object Registry {
                    @AutoInvoke(EventType.PLUGIN_STOP)
                    fun flush() {}
                }

                @LocalBean
                class Banner(val clock: Clock) {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    internal fun show(clock: Clock) {}
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements.filter { it.target is FunctionTarget }
        val functions = elements.map { it.target as FunctionTarget }.associateBy { "${it.className}.${it.functionName}" }
        assertEquals(
            setOf("com.example.alpha.AlphaPlugin.announce", "com.example.alpha.AlphaPlugin.warmUp", "com.example.alpha.Registry.flush", "com.example.alpha.Banner.show"),
            functions.keys,
        )
        assertTrue(elements.all { it.processorClass == LifecycleProcessors.AUTO_INVOKE })
        val warmUp = functions.getValue("com.example.alpha.AlphaPlugin.warmUp")
        assertEquals(FunctionOwner.PLUGIN, warmUp.owner)
        assertEquals(listOf(Parameter("com.example.alpha.Clock")), warmUp.parameters)
        assertEquals(Invoker.Reflective("com.example.alpha.AlphaPlugin", "warmUp", listOf("com.example.alpha.Clock")), warmUp.invoker)
        assertIs<Invoker.Direct>(functions.getValue("com.example.alpha.AlphaPlugin.announce").invoker)
        assertEquals(FunctionOwner.OBJECT, functions.getValue("com.example.alpha.Registry.flush").owner)
        assertEquals(FunctionOwner.INSTANCE, functions.getValue("com.example.alpha.Banner.show").owner)
        val phases = elements.associate { (it.target as FunctionTarget).functionName to it.phase }
        assertEquals(EventType.PLUGIN_RELOAD_OR_START, phases["announce"])
        assertEquals(EventType.PLUGIN_STOP, phases["flush"])
        assertEquals(
            AnnotationOccurrence("com.github.marcoral.versatia.lifecycle.AutoInvoke", mapOf("phase" to "PLUGIN_STOP")),
            elements.single { (it.target as FunctionTarget).functionName == "flush" }.annotation,
        )

        val registry = result.classLoader.loadClass("com.example.alpha.Registry").getDeclaredField("INSTANCE").get(null)
        functions.getValue("com.example.alpha.Registry.flush").invoker.invoke(result.classLoader, registry, emptyList())
    }

    @Test
    fun `rejects AutoInvoke functions the runtime could not call`() {
        val result = compile(
            SourceFile.kotlin(
                "BadInvoked.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                class AlphaPlugin : VersatiaPlugin() {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun generic(value: List<String>) {}

                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun String.extension() {}
                }

                class Plain {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun nobody() {}
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Injected parameter value must not be generic")
        assertContains(result.messages, "AlphaPlugin.extension must not be an extension function to be enhanced")
        assertContains(result.messages, "Plain.nobody cannot be enhanced: Plain is neither the plugin's main class, an object nor a bean")
        assertContains(result.messages, "BadInvoked.kt:")
    }

    @Test
    fun `rejects dependency cycles at compile time`() {
        val result = compile(
            SourceFile.kotlin(
                "Cycle.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Alpha(val beta: Beta)

                @LocalBean
                class Beta(val alpha: Alpha)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Dependency cycle: Alpha -> Beta -> Alpha")
        assertContains(result.messages, "Cycle.kt")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
    }

    @Test
    fun `records Primary and Qualifier as attributes of classes and parameters`() {
        val result = compile(
            SourceFile.kotlin(
                "Greeters.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                interface Greeter

                @LocalBean @Primary
                class FriendlyGreeter : Greeter

                @LocalBean @Qualifier("grumpy")
                class UnfriendlyGreeter : Greeter

                @LocalBean
                class Banner(val greeter: Greeter)

                @LocalBean
                class ComplaintDesk(@Qualifier("grumpy") val greeter: Greeter)

                class AlphaPlugin : VersatiaPlugin() {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun complain(@Qualifier("grumpy") greeter: Greeter) {}
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val targets = result.descriptor().classTargets()
        assertEquals(listOf(AnnotationOccurrence(primary)), targets.getValue("com.example.alpha.FriendlyGreeter").annotations)
        assertEquals(listOf(AnnotationOccurrence(qualifier, mapOf("value" to "grumpy"))), targets.getValue("com.example.alpha.UnfriendlyGreeter").annotations)
        assertEquals(listOf(Parameter("com.example.alpha.Greeter")), targets.getValue("com.example.alpha.Banner").constructorParameters)
        val grumpy = Parameter("com.example.alpha.Greeter", listOf(AnnotationOccurrence(qualifier, mapOf("value" to "grumpy"))))
        assertEquals(listOf(grumpy), targets.getValue("com.example.alpha.ComplaintDesk").constructorParameters)
        val complain = result.descriptor().elements.map { it.target }.filterIsInstance<FunctionTarget>().single()
        assertEquals(listOf(grumpy), complain.parameters)
    }

    @Test
    fun `rejects unresolvable candidates, duplicate names and misplaced annotations`() {
        val result = compile(
            SourceFile.kotlin(
                "BadGreeters.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier

                interface Greeter

                @LocalBean
                class FriendlyGreeter : Greeter

                @LocalBean @Qualifier("friendlyGreeter")
                class UnfriendlyGreeter : Greeter

                @LocalBean
                class Banner(val greeter: Greeter)

                @Primary
                class NotABean
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Bean name \"friendlyGreeter\" is used by com.example.alpha.FriendlyGreeter, com.example.alpha.UnfriendlyGreeter")
        assertContains(result.messages, "Dependency com.example.alpha.Greeter of com.example.alpha.Banner is ambiguous")
        assertContains(result.messages, "@Primary on NotABean has no effect: the class is not a bean")
        assertContains(result.messages, "BadGreeters.kt:")
    }

    @Test
    fun `rejects beans without a usable constructor`() {
        val result = compile(
            SourceFile.kotlin(
                "Bad.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                abstract class Base

                @LocalBean
                class Nullable(val base: Base?)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Base must be a concrete, non-inner class to be a bean")
        assertContains(result.messages, "Injected parameter base must not be nullable")
    }

    @Test
    fun `rejects attribute arguments it cannot record`() {
        val result = compile(
            SourceFile.kotlin(
                "Attributes.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaAttribute
                import com.github.marcoral.versatia.di.LocalBean

                annotation class Meta(val text: String)

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaAttribute
                annotation class Tagged(val meta: Meta)

                @LocalBean @Tagged(Meta("x"))
                class Clock
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Cannot record argument \"meta\" of @Tagged in the descriptor")
        assertContains(result.messages, "Attributes.kt:")
    }

    @Test
    fun `finds the main class in the sources when no option is given`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean

                class AlphaPlugin : VersatiaPlugin()

                @LocalBean
                class Inventory
                """,
            ),
            SourceFile.kotlin(
                "Other.kt",
                """
                package com.example.alpha.other

                @com.github.marcoral.versatia.di.LocalBean
                class Elsewhere
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor("com.example.alpha").elements
        assertEquals(2, elements.count { it.target is ClassTarget })
        assertEquals("com.example.alpha.AlphaPlugin", (elements.first { it.target is PluginTarget }.target as PluginTarget).className)
    }

    @Test
    fun `records the main class as a local bean of its plugin`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier
                import org.bukkit.plugin.java.JavaPlugin

                @Primary
                class AlphaPlugin : VersatiaPlugin()

                @LocalBean
                class Settings(val plugin: AlphaPlugin, @Qualifier("plugin") val generic: JavaPlugin)
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements
        val element = elements.first()
        assertEquals(BeanProcessors.PLUGIN, element.processorClass)
        assertEquals(EventType.PLUGIN_START_ONLY, element.phase)
        assertEquals(AnnotationOccurrence(PluginBean::class.java.name), element.annotation, "inherited from VersatiaPlugin")
        assertEquals(ResourceProcessors.EXTRACT, elements[1].processorClass, "VersatiaPlugin's second enhancement")
        assertEquals(AnnotationOccurrence(ExtractResources::class.java.name), elements[1].annotation)
        assertEquals(element.target, elements[1].target)
        val plugin = assertIs<PluginTarget>(element.target)
        assertEquals("com.example.alpha.AlphaPlugin", plugin.className)
        assertEquals(listOf(AnnotationOccurrence(primary)), plugin.annotations)
        assertContains(plugin.assignableTypes, "com.example.alpha.AlphaPlugin")
        assertContains(plugin.assignableTypes, "com.github.marcoral.versatia.VersatiaPlugin")
        assertContains(plugin.assignableTypes, "org.bukkit.plugin.java.JavaPlugin")
        assertContains(plugin.assignableTypes, "org.bukkit.plugin.Plugin")
        val settings = result.descriptor().classTargets().values.single()
        assertEquals(
            listOf(Parameter("com.example.alpha.AlphaPlugin"), Parameter("org.bukkit.plugin.java.JavaPlugin", listOf(AnnotationOccurrence(qualifier, mapOf("value" to "plugin"))))),
            settings.constructorParameters,
        )
    }

    @Test
    fun `rejects scope and qualifier annotations on the main class and the reserved name elsewhere`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier

                @LocalBean @Qualifier("alpha")
                class AlphaPlugin : VersatiaPlugin()

                @LocalBean @Qualifier("plugin")
                class Settings
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "AlphaPlugin is the plugin's main class and a local bean already; remove @LocalBean")
        assertContains(result.messages, "AlphaPlugin is the plugin's main class and its bean is always named \"plugin\"; remove @Qualifier")
        assertContains(result.messages, "Bean name \"plugin\" is reserved for the plugin's main class; rename com.example.alpha.Settings with @Qualifier")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
    }

    @Test
    fun `rejects a plugin yml naming a class that does not exist`() {
        val result = compile(SourceFile.kotlin("Empty.kt", "package com.example.alpha"), mainClass = "com.example.alpha.Missing")

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Main class com.example.alpha.Missing from")
        assertContains(result.messages, "is neither in the sources nor on the classpath")
    }

    @Test
    fun `asks for the option when the main class cannot be determined`() {
        val none = compile(SourceFile.kotlin("Empty.kt", "package com.example.alpha"), mainClass = null)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, none.exitCode)
        assertContains(none.messages, "No class extending org.bukkit.plugin.java.JavaPlugin")
        assertContains(none.messages, VersatiaSymbolProcessor.OPTION_PLUGIN_YML)

        val several = compile(
            SourceFile.kotlin(
                "Two.kt",
                """
                package com.example.alpha

                import org.bukkit.plugin.java.JavaPlugin

                class First : JavaPlugin()
                class Second : JavaPlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, several.exitCode)
        assertContains(several.messages, "Several classes extend org.bukkit.plugin.java.JavaPlugin: com.example.alpha.First, com.example.alpha.Second")
    }

    @Test
    fun `inherits enhancements from a plugin base class`() {
        val result = compile(
            SourceFile.kotlin(
                "Base.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.VersatiaPlugin

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaEnhancement("com.example.test.AuditEnhancementProcessor", enhancementPhase = EventType.PLUGIN_STOP)
                annotation class Audited(val channel: String)

                @Audited("console")
                abstract class BasePlugin : VersatiaPlugin()

                class AlphaPlugin : BasePlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val audit = result.descriptor().elements.single { it.processorClass == "com.example.test.AuditEnhancementProcessor" }
        assertEquals(EventType.PLUGIN_STOP, audit.phase)
        assertEquals(AnnotationOccurrence("com.example.alpha.Audited", mapOf("channel" to "console")), audit.annotation)
        assertEquals("com.example.alpha.AlphaPlugin", assertIs<PluginTarget>(audit.target).className)
    }

    @Test
    fun `prototypes take the consuming plugin only as a type every plugin has`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.ExposePrototype
                import com.github.marcoral.versatia.di.ExposeSingleton
                import org.bukkit.plugin.Plugin

                class AlphaPlugin : VersatiaPlugin()

                @ExposePrototype
                class Session(val plugin: VersatiaPlugin, val owner: Plugin)

                @ExposePrototype
                class Strict(val plugin: AlphaPlugin)

                @ExposeSingleton
                class Shared(val plugin: AlphaPlugin)
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "com.example.alpha.Strict is a prototype, so its com.example.alpha.AlphaPlugin parameter receives the consuming plugin; declare it as VersatiaPlugin (or a supertype), which every plugin is")
        assertFalse(result.messages.contains("Session is a prototype"), "VersatiaPlugin and Plugin are types every plugin has")
        assertFalse(result.messages.contains("Shared is a prototype"), "singletons keep the owner's instance")
    }
}

