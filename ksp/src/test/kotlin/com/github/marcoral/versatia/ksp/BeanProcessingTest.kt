package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.resources.ExtractResources
import com.github.marcoral.versatia.resources.ResourceProcessors
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** Beans, scopes, injection and the bean graph. */
@OptIn(ExperimentalCompilerApi::class)
class BeanProcessingTest : ProcessorTest() {
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
        assertEquals(Phase.PLUGIN_START_ONLY, element.phase)
        assertEquals(AnnotationOccurrence("com.example.alpha.Service"), element.annotation)
    }

    @Test
    fun `reads the phase from the property named by enhancementPhaseParamRefName`() {
        val result = compile(
            SourceFile.kotlin(
                "Phased.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean
                import com.github.marcoral.versatia.ksp.testscopes.PhasedBean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @Bean
                @VersatiaEnhancement("com.example.RunAtEnhancementProcessor", enhancementPhaseParamRefName = "at")
                annotation class RunAt(val at: Phase, val note: String = "")

                @RunAt(Phase.PLUGIN_STOP, note = "late")
                class FromSource

                @PhasedBean(Phase.PLUGIN_RELOAD_ONLY)
                class FromClasspath
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val elements = result.descriptor().elements.filter { it.target is ClassTarget }.associateBy { (it.target as ClassTarget).className }
        assertEquals(Phase.PLUGIN_STOP, elements.getValue("com.example.alpha.FromSource").phase)
        assertEquals(Phase.PLUGIN_RELOAD_ONLY, elements.getValue("com.example.alpha.FromClasspath").phase)
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
            setOf(BeanProcessors.LOCAL to Phase.PLUGIN_START_ONLY, "com.example.test.AuditEnhancementProcessor" to Phase.PLUGIN_STOP),
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P")
                annotation class NoPhase

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhase = Phase.PLUGIN_START_ONLY, enhancementPhaseParamRefName = "at")
                annotation class BothPhases(val at: Phase)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhaseParamRefName = "missing")
                annotation class MissingProperty(val at: Phase)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("com.example.P", enhancementPhaseParamRefName = "at")
                annotation class WrongType(val at: String)

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("", enhancementPhase = Phase.PLUGIN_START_ONLY)
                annotation class NoProcessor

                @NoPhase class A
                @BothPhases(Phase.PLUGIN_STOP) class B
                @MissingProperty(Phase.PLUGIN_STOP) class C
                @WrongType("x") class D
                @NoProcessor class E
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "on NoPhase declares no phase; set enhancementPhase or enhancementPhaseParamRefName")
        assertContains(result.messages, "on BothPhases sets both enhancementPhase and enhancementPhaseParamRefName")
        assertContains(result.messages, "on MissingProperty refers to property \"missing\", which MissingProperty does not declare")
        assertContains(result.messages, "Property \"at\" of WrongType must be of type Phase")
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean
                import com.github.marcoral.versatia.di.BeanProcessors

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @Bean
                @VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = Phase.PLUGIN_STOP)
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
        assertContains(result.messages, "Dependency cycle: com.example.alpha.Alpha -> com.example.alpha.Beta -> com.example.alpha.Alpha")
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

                import com.github.marcoral.versatia.Phase
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
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
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
        assertContains(result.messages, "Base cannot be a bean: it is abstract; annotate a concrete subclass")
        assertContains(result.messages, "Injected parameter base of Nullable must not be nullable")
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
        assertEquals(Phase.PLUGIN_RELOAD_OR_START, element.phase)
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
    fun `rejects every kind of class that cannot be a bean, saying why`() {
        val result = compile(
            SourceFile.kotlin(
                "Kinds.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.di.ExposeSingleton
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @LocalBean object Registry
                @LocalBean interface Service
                @LocalBean sealed class Shape
                @LocalBean enum class Mode { EASY }
                class Outer { @LocalBean inner class Inner }
                @LocalBean class Box<T>
                @LocalBean class Twice {
                    constructor()
                    constructor(size: Int)
                }
                @LocalBean @Qualifier(" ") class Blank
                @LocalBean @ExposeSingleton class Both
                @Qualifier("loose") class NotABean

                @LocalBean
                class Rejected(val shape: Shape?) {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun start() {}
                }
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Registry cannot be a bean: it is an object, which is its own single instance; use it directly")
        assertContains(result.messages, "Service cannot be a bean: it is an interface; annotate a class implementing it")
        assertContains(result.messages, "Shape cannot be a bean: it is abstract; annotate a concrete subclass")
        assertContains(result.messages, "Mode cannot be a bean: it is an enum class")
        assertContains(result.messages, "Inner cannot be a bean: it is an inner class")
        assertContains(result.messages, "Box must not have type parameters to be a bean")
        assertContains(result.messages, "Twice must have a primary constructor or exactly one constructor to be a bean")
        assertContains(result.messages, "@Qualifier on Blank must not be blank")
        assertContains(result.messages, "Both has more than one bean scope: LocalBean, ExposeSingleton")
        assertContains(result.messages, "@Qualifier on NotABean has no effect: the class is not a bean")
        assertContains(result.messages, "Injected parameter shape of Rejected must not be nullable")
        assertFalse("Rejected.start cannot be enhanced" in result.messages, "a rejected bean is still a bean for its members")
    }

    @Test
    fun `injects through the primary constructor when there are secondary ones`() {
        val result = compile(
            SourceFile.kotlin(
                "Store.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Clock

                @LocalBean
                class Store(val clock: Clock) {
                    constructor() : this(Clock())
                }
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(listOf(Parameter("com.example.alpha.Clock")), result.descriptor().classTargets().getValue("com.example.alpha.Store").constructorParameters)
    }

    @Test
    fun `rejects dependencies no plugin can provide`() {
        val result = compile(
            SourceFile.kotlin(
                "Shop.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.PluginRegistry
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.ksp.testscopes.PrivateClock
                import com.github.marcoral.versatia.ksp.testscopes.SharedClock
                import java.util.UUID
                import org.bukkit.Server
                import org.bukkit.plugin.java.JavaPlugin

                class BarePlugin : JavaPlugin()

                class Clock

                interface Greeter

                @LocalBean
                class Shop(
                    val clock: Clock,
                    val greeter: Greeter,
                    val plugin: BarePlugin,
                    val name: String,
                    val id: UUID,
                    val local: PrivateClock,
                    val shared: SharedClock,
                    val server: Server,
                    val registry: PluginRegistry,
                )
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val shop = "of com.example.alpha.Shop has no provider"
        assertContains(result.messages, "Dependency com.example.alpha.Clock $shop: Clock is declared in this plugin, so no other plugin can provide it; give a class providing it a bean scope such as @LocalBean")
        assertContains(result.messages, "Dependency com.example.alpha.Greeter $shop: Greeter is declared in this plugin")
        assertContains(result.messages, "Dependency com.example.alpha.BarePlugin $shop: BarePlugin is the plugin's main class, which is a bean only with @PluginBean")
        assertContains(result.messages, "Dependency kotlin.String $shop: kotlin.String is final and no bean scope exposes it, so no plugin can provide it")
        assertContains(result.messages, "Dependency java.util.UUID $shop")
        assertContains(result.messages, "Dependency com.github.marcoral.versatia.ksp.testscopes.PrivateClock $shop")
        assertFalse("SharedClock $shop" in result.messages, "another plugin exposes it")
        assertFalse("Server $shop" in result.messages, "an interface may be exposed by another plugin")
        assertFalse("PluginRegistry $shop" in result.messages, "VersatiaCore exposes it")
    }

    @Test
    fun `reports a broken scope once, at the class using it when the scope is compiled`() {
        val result = compile(
            SourceFile.kotlin(
                "Scopes.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                annotation class Bare

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) @Bean
                @VersatiaEnhancement("")
                annotation class Broken

                @Broken class First
                @Broken class Second
                @Bare class Third
                """,
            ),
            SourceFile.kotlin(
                "Ledger.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ksp.testscopes.BrokenBean

                @BrokenBean
                class Ledger
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertEquals(1, result.messages.split("@VersatiaEnhancement on Broken has no processorClass").size - 1, result.messages)
        assertContains(result.messages, "Bare inherits @Bean but no @VersatiaEnhancement names its processor")
        assertTrue(Regex("Ledger\\.kt:\\d+.*@VersatiaEnhancement on BrokenBean has no processorClass").containsMatchIn(result.messages), result.messages)
    }

    @Test
    fun `rejects a type that does not resolve with a plain error`() {
        val result = compile(
            SourceFile.kotlin(
                "Typo.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Shop(val clock: Clok)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Typo.kt:")
        assertContains(result.messages, "Type Clok cannot be resolved, so the Versatia descriptor cannot be generated")
        assertFalse("Exception" in result.messages, result.messages)
    }

    @Test
    fun `rejects an occurrence that leaves the referenced phase at UNSET`() {
        val result = compile(
            SourceFile.kotlin(
                "Phased.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.di.Bean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @Bean
                @VersatiaEnhancement("com.example.RunAtEnhancementProcessor", enhancementPhaseParamRefName = "at")
                annotation class RunAt(val at: Phase)

                @RunAt(Phase.UNSET)
                class Never
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@RunAt must set at to a phase other than UNSET; it decides when the enhancement runs")
    }
}
