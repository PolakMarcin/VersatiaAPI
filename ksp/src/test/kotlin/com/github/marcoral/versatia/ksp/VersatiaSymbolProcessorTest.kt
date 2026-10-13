package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.Setter
import com.github.marcoral.versatia.engine.descriptor.TypeReference
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
                import com.github.marcoral.versatia.config.ConfigProcessors
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
        assertEquals(MemberOwner.PLUGIN, warmUp.owner)
        assertEquals(listOf(Parameter("com.example.alpha.Clock")), warmUp.parameters)
        assertEquals(Invoker.Reflective("com.example.alpha.AlphaPlugin", "warmUp", listOf("com.example.alpha.Clock")), warmUp.invoker)
        assertIs<Invoker.Direct>(functions.getValue("com.example.alpha.AlphaPlugin.announce").invoker)
        assertEquals(MemberOwner.OBJECT, functions.getValue("com.example.alpha.Registry.flush").owner)
        assertEquals(MemberOwner.INSTANCE, functions.getValue("com.example.alpha.Banner.show").owner)
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
        assertEquals(EventType.PLUGIN_RELOAD_OR_START, element.phase)
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
    fun `switches an inherited enhancement off`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import com.github.marcoral.versatia.VersatiaPlugin

                @DisableVersatiaFeatures(extractResources = true)
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(listOf(BeanProcessors.PLUGIN), result.descriptor().elements.map { it.processorClass })
    }

    @Test
    fun `lets the nearest declaration decide`() {
        val reenabled = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.resources.ExtractResources

                @DisableVersatiaFeatures(extractResources = true)
                abstract class QuietPlugin : VersatiaPlugin()

                @ExtractResources
                class AlphaPlugin : QuietPlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, reenabled.exitCode, reenabled.messages)
        assertEquals(1, reenabled.descriptor().elements.count { it.processorClass == ResourceProcessors.EXTRACT })

        val overridden = compile(
            SourceFile.kotlin(
                "Plugin.kt",
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

                @Audited("file")
                class AlphaPlugin : BasePlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, overridden.exitCode, overridden.messages)
        val audit = overridden.descriptor().elements.single { it.processorClass == "com.example.test.AuditEnhancementProcessor" }
        assertEquals(mapOf("channel" to "file"), audit.annotation.arguments)
    }

    @Test
    fun `rejects plugin enhancements and switches outside the main class`() {
        val result = compile(
            SourceFile.kotlin(
                "Beans.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.resources.ExtractResources

                @LocalBean @ExtractResources
                class Config

                @DisableVersatiaFeatures(extractResources = true)
                object Tools
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@ExtractResources on Config has no effect: it belongs on the plugin's main class or on a base class of it")
        assertContains(result.messages, "@DisableVersatiaFeatures on Tools has no effect")
    }

    @Test
    fun `rejects switches that change nothing`() {
        val idle = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import com.github.marcoral.versatia.VersatiaPlugin

                @DisableVersatiaFeatures
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, idle.exitCode)
        assertContains(idle.messages, "@DisableVersatiaFeatures on AlphaPlugin switches nothing off")

        val bare = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import org.bukkit.plugin.java.JavaPlugin

                @DisableVersatiaFeatures(extractResources = true)
                class BarePlugin : JavaPlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, bare.exitCode)
        assertContains(bare.messages, "BarePlugin disables @ExtractResources, which neither BarePlugin nor its supertypes carry")

        val contradictory = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.DisableVersatiaFeatures
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.resources.ExtractResources

                @ExtractResources
                @DisableVersatiaFeatures(extractResources = true)
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, contradictory.exitCode)
        assertContains(contradictory.messages, "AlphaPlugin both carries and disables @ExtractResources")
    }

    @Test
    fun `enhances the subclasses of a type named on the plugin's base class`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.VersatiaSubclassEnhancement
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier

                interface Auditable

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, EventType.PLUGIN_RELOAD_OR_START)
                annotation class AuditSubclasses

                @AuditSubclasses
                abstract class AuditedPlugin : VersatiaPlugin()

                class AlphaPlugin : AuditedPlugin(), Auditable

                @LocalBean
                class Clock

                class Ledger(@Qualifier("clock") val clock: Clock) : Auditable

                object Console : Auditable

                private object Vault : Auditable

                abstract class Template : Auditable

                class NotAudited
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val audited = result.descriptor().elements.filter { it.processorClass == "com.example.test.AuditEnhancementProcessor" }
        assertEquals(listOf("com.example.alpha.AlphaPlugin", "com.example.alpha.Console", "com.example.alpha.Ledger", "com.example.alpha.Vault"), audited.map { it.target.className })
        assertTrue(audited.all { it.phase == EventType.PLUGIN_RELOAD_OR_START })
        assertTrue(audited.all { it.origin == "com.example.alpha.AuditedPlugin" }, "the origin is where @AuditSubclasses was written")
        assertTrue(audited.all { it.annotation == AnnotationOccurrence("com.example.alpha.AuditSubclasses") })
        assertIs<PluginTarget>(audited[0].target)
        val ledger = assertIs<ClassTarget>(audited[2].target)
        assertEquals(listOf(Parameter("com.example.alpha.Clock", listOf(AnnotationOccurrence(qualifier, mapOf("value" to "clock"))))), ledger.constructorParameters)
        assertIs<Instantiator.Direct>(ledger.instantiator)
        assertIs<Instantiator.Direct>(assertIs<ClassTarget>(audited[1].target).instantiator)
        assertEquals(Instantiator.ObjectInstance("com.example.alpha.Vault"), assertIs<ClassTarget>(audited[3].target).instantiator)
        val pluginBean = result.descriptor().elements.first { it.processorClass == BeanProcessors.PLUGIN }
        assertEquals("com.github.marcoral.versatia.VersatiaPlugin", pluginBean.origin)
    }

    @Test
    fun `rejects a matched class the runtime could not instantiate`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.VersatiaSubclassEnhancement

                interface Auditable

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, EventType.PLUGIN_START_ONLY)
                annotation class AuditSubclasses

                @AuditSubclasses
                class AlphaPlugin : VersatiaPlugin()

                class Ledger(val missing: String?) : Auditable

                class Twice : Auditable {
                    constructor()
                    constructor(name: String)
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Injected parameter missing must not be nullable")
        assertContains(result.messages, "Twice is enhanced by @AuditSubclasses on AlphaPlugin and must have exactly one constructor")
    }

    @Test
    fun `records FriendlyName and ConditionalOnPlugin as attributes`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.FriendlyName
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @FriendlyName("shop")
                class AlphaPlugin : VersatiaPlugin()

                @ConditionalOnPlugin("Vault")
                @LocalBean
                class Bridge {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun connect() {}
                }

                @ConditionalOnPlugin("CommandAPI", "Vault")
                object Hooks {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun hook() {}

                    @ConditionalOnPlugin("Essentials")
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun chat() {}
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements
        val plugin = assertIs<PluginTarget>(elements.first().target)
        assertEquals(listOf(AnnotationOccurrence("com.github.marcoral.versatia.FriendlyName", mapOf("value" to "shop"))), plugin.annotations)
        val conditional = "com.github.marcoral.versatia.ConditionalOnPlugin"
        val onVault = AnnotationOccurrence(conditional, mapOf("value" to listOf("Vault")))
        val onBoth = AnnotationOccurrence(conditional, mapOf("value" to listOf("CommandAPI", "Vault")))
        assertEquals(listOf(onVault), result.descriptor().classTargets().getValue("com.example.alpha.Bridge").annotations)
        val functions = elements.map { it.target }.filterIsInstance<FunctionTarget>().associateBy { it.functionName }
        assertEquals(emptyList(), functions.getValue("connect").annotations)
        assertEquals(listOf(onVault), functions.getValue("connect").classAnnotations, "the bean's attributes travel with its functions")
        assertEquals(listOf(onBoth), functions.getValue("hook").classAnnotations, "the object's attributes travel with its functions")
        assertEquals(listOf(AnnotationOccurrence(conditional, mapOf("value" to listOf("Essentials")))), functions.getValue("chat").annotations)
        assertEquals(listOf(onBoth), functions.getValue("chat").classAnnotations)
    }

    @Test
    fun `rejects FriendlyName and ConditionalOnPlugin where they have no effect`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.EventType
                import com.github.marcoral.versatia.FriendlyName
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @FriendlyName("two words") @ConditionalOnPlugin("Vault")
                class AlphaPlugin : VersatiaPlugin()

                @FriendlyName("config") @LocalBean
                class Config

                @ConditionalOnPlugin("Vault")
                object Tools {
                    @ConditionalOnPlugin("Vault")
                    fun plain() {}
                }

                @ConditionalOnPlugin() @LocalBean
                class Bridge
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@FriendlyName on AlphaPlugin must be one word without spaces")
        assertContains(result.messages, "@ConditionalOnPlugin cannot apply to AlphaPlugin, the plugin's main class")
        assertContains(result.messages, "@FriendlyName on Config has no effect: it belongs on the plugin's main class")
        assertContains(result.messages, "@ConditionalOnPlugin on Tools has no effect: Tools is not enhanced and declares no enhanced function or property")
        assertContains(result.messages, "@ConditionalOnPlugin on Tools.plain() has no effect: the function is not enhanced")
        assertContains(result.messages, "@ConditionalOnPlugin on Bridge must name plugins, one word each")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
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

    @Test
    fun `describes Value properties of the plugin, objects and beans`() {
        val result = compile(
            SourceFile.kotlin(
                "Valued.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.util.type.Percent

                enum class Mode { EASY, HARD }

                class AlphaPlugin : VersatiaPlugin() {
                    @Value("motd")
                    lateinit var motd: String

                    @Value("mode", file = "rules.yml")
                    private var mode: Mode = Mode.EASY
                }

                object Limits {
                    @Value("limits.slots")
                    var slots: List<Int> = emptyList()

                    @Value("limits.tax")
                    var tax: Percent? = null
                }

                @LocalBean
                class Shop {
                    @Value("shop.open")
                    var open = false
                        private set
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements.filter { it.target is PropertyTarget }
        val properties = elements.map { it.target as PropertyTarget }.associateBy { "${it.className}.${it.propertyName}" }
        assertEquals(
            setOf("com.example.alpha.AlphaPlugin.motd", "com.example.alpha.AlphaPlugin.mode", "com.example.alpha.Limits.slots", "com.example.alpha.Limits.tax", "com.example.alpha.Shop.open"),
            properties.keys,
        )
        assertTrue(elements.all { it.processorClass == ConfigProcessors.VALUE && it.phase == EventType.PLUGIN_RELOAD_OR_START })
        assertEquals(
            AnnotationOccurrence("com.github.marcoral.versatia.config.Value", mapOf("key" to "mode", "file" to "rules.yml", "mapper" to "com.github.marcoral.versatia.config.NoMapper")),
            elements.single { (it.target as PropertyTarget).propertyName == "mode" }.annotation,
        )
        assertEquals("config.yml", elements.single { (it.target as PropertyTarget).propertyName == "motd" }.annotation.arguments["file"])

        val motd = properties.getValue("com.example.alpha.AlphaPlugin.motd")
        assertEquals(MemberOwner.PLUGIN, motd.owner)
        assertEquals(TypeReference("kotlin.String"), motd.type)
        assertIs<Setter.Direct>(motd.setter)
        assertEquals(Setter.Reflective("com.example.alpha.AlphaPlugin", "mode"), properties.getValue("com.example.alpha.AlphaPlugin.mode").setter)
        assertEquals(TypeReference("com.example.alpha.Mode"), properties.getValue("com.example.alpha.AlphaPlugin.mode").type)
        val slots = properties.getValue("com.example.alpha.Limits.slots")
        assertEquals(MemberOwner.OBJECT, slots.owner)
        assertEquals(TypeReference("kotlin.collections.List", listOf(TypeReference("kotlin.Int"))), slots.type)
        assertEquals(TypeReference("com.github.marcoral.versatia.util.type.Percent", nullable = true), properties.getValue("com.example.alpha.Limits.tax").type)
        val open = properties.getValue("com.example.alpha.Shop.open")
        assertEquals(MemberOwner.INSTANCE, open.owner)
        assertEquals(Setter.Reflective("com.example.alpha.Shop", "open"), open.setter, "a private setter needs reflection")

        val limits = result.classLoader.loadClass("com.example.alpha.Limits").getDeclaredField("INSTANCE").get(null)
        slots.setter.set(result.classLoader, limits, listOf(1, 2))
        assertEquals(listOf(1, 2), limits.javaClass.getMethod("getSlots").invoke(limits))
        val shop = result.classLoader.loadClass("com.example.alpha.Shop").getDeclaredConstructor().newInstance()
        open.setter.set(result.classLoader, shop, true)
        assertEquals(true, shop.javaClass.getMethod("getOpen").invoke(shop))
    }

    @Test
    fun `rejects Value properties the runtime could not set`() {
        val result = compile(
            SourceFile.kotlin(
                "BadValued.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.Value
                import java.util.Date

                @Value("top")
                var top = 0

                class AlphaPlugin : VersatiaPlugin() {
                    @Value("fixed")
                    val fixed = 0

                    @Value("when")
                    var date: Date? = null

                    @Value("holes")
                    var holes: List<Int?> = emptyList()

                    @Value("keyed")
                    var keyed: Map<Int, String> = emptyMap()

                    @Value("mutable")
                    var mutable: MutableList<String> = mutableListOf()
                }

                class Plain {
                    @Value("nobody")
                    var nobody = 0
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "top cannot be enhanced: it is a top-level property")
        assertContains(result.messages, "AlphaPlugin.fixed must be a var to be enhanced")
        assertContains(result.messages, "@Value on AlphaPlugin.date: Date? is not a supported type; supported are")
        assertContains(result.messages, "@Value on AlphaPlugin.holes: the elements of List<Int?> must not be nullable")
        assertContains(result.messages, "@Value on AlphaPlugin.keyed: the keys of Map<Int, String> must be String")
        assertContains(result.messages, "@Value on AlphaPlugin.mutable: MutableList<String> is not a supported type")
        assertContains(result.messages, "Plain.nobody cannot be enhanced: Plain is neither the plugin's main class, an object nor a bean")
        assertContains(result.messages, "BadValued.kt:")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
    }

    @Test
    fun `records Value mappers and checks what they map to`() {
        val mappers = """
            package com.example.alpha

            import com.github.marcoral.versatia.config.ValueMapper
            import com.github.marcoral.versatia.util.config.getIntOrThrow
            import com.github.marcoral.versatia.util.config.getLongOrThrow
            import org.bukkit.configuration.ConfigurationSection

            object TicksFromSeconds : ValueMapper<Long> {
                override fun map(section: ConfigurationSection, key: String) = section.getLongOrThrow(key) * 20
            }

            data class Range(val min: Int, val max: Int) {
                object Mapper : ValueMapper<Range> {
                    override fun map(section: ConfigurationSection, key: String) = Range(section.getIntOrThrow("${'$'}key.min"), section.getIntOrThrow("${'$'}key.max"))
                }
            }

            class Scaled(private val factor: Int = 2) : ValueMapper<Int> {
                override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key) * factor
            }

            class Needy(private val factor: Int) : ValueMapper<Int> {
                override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key) * factor
            }
        """
        val accepted = compile(
            SourceFile.kotlin("Mappers.kt", mappers),
            SourceFile.kotlin(
                "Mapped.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.Value

                class AlphaPlugin : VersatiaPlugin() {
                    @Value("restock", mapper = TicksFromSeconds::class)
                    var restock = 0L

                    @Value("range", mapper = Range.Mapper::class)
                    var range: Range? = null

                    @Value("scaled", mapper = Scaled::class)
                    var scaled = 0
                }
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, accepted.exitCode, accepted.messages)
        val mapperOf = accepted.descriptor().elements.filter { it.target is PropertyTarget }.associate { (it.target as PropertyTarget).propertyName to it.annotation.arguments["mapper"] }
        assertEquals(mapOf("restock" to "com.example.alpha.TicksFromSeconds", "range" to "com.example.alpha.Range.Mapper", "scaled" to "com.example.alpha.Scaled"), mapperOf)

        val rejected = compile(
            SourceFile.kotlin("Mappers.kt", mappers),
            SourceFile.kotlin(
                "BadMapped.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.Value

                class AlphaPlugin : VersatiaPlugin() {
                    @Value("restock", mapper = TicksFromSeconds::class)
                    var restock = 0

                    @Value("needy", mapper = Needy::class)
                    var needy = 0
                }
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(rejected.messages, "@Value on AlphaPlugin.restock: mapper TicksFromSeconds maps to Long, which Int cannot hold")
        assertContains(rejected.messages, "@Value on AlphaPlugin.needy: mapper Needy must be an object or a class with a no-argument constructor")
    }

    @Test
    fun `fills the cell of a Value property holding a PreviousValueTracking`() {
        val accepted = compile(
            SourceFile.kotlin(
                "Tracked.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.PreviousValueTracking
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean

                class AlphaPlugin : VersatiaPlugin()

                object Timers {
                    @Value("interval")
                    val interval = PreviousValueTracking<Int>()
                }

                @LocalBean
                class Shop {
                    @Value("days")
                    private val days = PreviousValueTracking<List<Int>?>()

                    fun days() = days
                }
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, accepted.exitCode, accepted.messages)
        val properties = accepted.descriptor().elements.map { it.target }.filterIsInstance<PropertyTarget>().associateBy { it.propertyName }
        val interval = properties.getValue("interval")
        assertEquals(TypeReference("com.github.marcoral.versatia.config.PreviousValueTracking", listOf(TypeReference("kotlin.Int"))), interval.type)
        assertIs<Setter.Direct>(interval.setter)
        val days = properties.getValue("days")
        assertEquals(Setter.Reflective("com.example.alpha.Shop", "days", "value"), days.setter, "a private property is read reflectively, its cell filled")

        val timers = accepted.classLoader.loadClass("com.example.alpha.Timers").getDeclaredField("INSTANCE").get(null)
        interval.setter.set(accepted.classLoader, timers, 5)
        interval.setter.set(accepted.classLoader, timers, 8)
        assertEquals("PreviousValueTracking(8, previous=5)", timers.javaClass.getMethod("getInterval").invoke(timers).toString())
        val shop = accepted.classLoader.loadClass("com.example.alpha.Shop").getDeclaredConstructor().newInstance()
        days.setter.set(accepted.classLoader, shop, listOf(1, 7))
        assertEquals("PreviousValueTracking([1, 7], previous=none)", shop.javaClass.getMethod("days").invoke(shop).toString())

        val rejected = compile(
            SourceFile.kotlin(
                "BadTracked.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.PreviousValueTracking
                import com.github.marcoral.versatia.config.Value
                import java.util.Date

                class AlphaPlugin : VersatiaPlugin()

                object Timers {
                    @Value("missing")
                    val missing: PreviousValueTracking<Int>? = null

                    @Value("date")
                    val date = PreviousValueTracking<Date>()
                }
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(rejected.messages, "@Value on Timers.missing: a PreviousValueTracking must not be nullable; the runtime fills the cell the property holds")
        assertContains(rejected.messages, "@Value on Timers.date: Date is not a supported type")
    }
}
