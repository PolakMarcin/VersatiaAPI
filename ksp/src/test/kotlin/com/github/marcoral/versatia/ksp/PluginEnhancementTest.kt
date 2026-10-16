package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.resources.ResourceProcessors
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** Enhancements of the main class, switches and subclass enhancements. */
@OptIn(ExperimentalCompilerApi::class)
class PluginEnhancementTest : ProcessorTest() {
    @Test
    fun `inherits enhancements from a plugin base class`() {
        val result = compile(
            SourceFile.kotlin(
                "Base.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.VersatiaPlugin

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaEnhancement("com.example.test.AuditEnhancementProcessor", enhancementPhase = Phase.PLUGIN_STOP)
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
        assertEquals(Phase.PLUGIN_STOP, audit.phase)
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.VersatiaPlugin

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaEnhancement("com.example.test.AuditEnhancementProcessor", enhancementPhase = Phase.PLUGIN_STOP)
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
        assertContains(result.messages, "@ExtractResources on Config has no effect: it belongs on the plugin's main class or on a supertype of it")
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.VersatiaSubclassEnhancement
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier

                interface Auditable

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, Phase.PLUGIN_RELOAD_OR_START)
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
        assertTrue(audited.all { it.phase == Phase.PLUGIN_RELOAD_OR_START })
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.VersatiaSubclassEnhancement

                interface Auditable

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, Phase.PLUGIN_START_ONLY)
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
        assertContains(result.messages, "Injected parameter missing of Ledger must not be nullable")
        assertContains(result.messages, "Twice is enhanced by @AuditSubclasses on AlphaPlugin and must have a primary constructor or exactly one constructor")
    }

    @Test
    fun `inherits enhancements from an interface of the main class in the sources`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.VersatiaPlugin

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaEnhancement("com.example.test.AuditEnhancementProcessor", enhancementPhase = Phase.PLUGIN_STOP)
                annotation class Audited

                @Audited
                interface AuditedPlugin

                class AlphaPlugin : VersatiaPlugin(), AuditedPlugin
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val audit = result.descriptor().elements.single { it.processorClass == "com.example.test.AuditEnhancementProcessor" }
        assertEquals("com.example.alpha.AuditedPlugin", audit.origin)
        assertEquals("com.example.alpha.AlphaPlugin", assertIs<PluginTarget>(audit.target).className)
    }

    @Test
    fun `skips sealed classes and rejects enums, companions, inner and generic classes matched by a subclass enhancement`() {
        val audited = """
            package com.example.alpha

            import com.github.marcoral.versatia.Phase
            import com.github.marcoral.versatia.VersatiaPlugin
            import com.github.marcoral.versatia.VersatiaSubclassEnhancement

            interface Auditable

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, Phase.PLUGIN_START_ONLY)
            annotation class AuditSubclasses

            @AuditSubclasses
            class AlphaPlugin : VersatiaPlugin()
        """
        val sealed = compile(
            SourceFile.kotlin("Plugin.kt", audited),
            SourceFile.kotlin(
                "Shapes.kt",
                """
                package com.example.alpha

                sealed class Shape : Auditable {
                    class Circle : Shape()
                }
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, sealed.exitCode, sealed.messages)
        val classes = sealed.descriptor().elements.filter { it.processorClass == "com.example.test.AuditEnhancementProcessor" }.map { it.target.className }
        assertEquals(listOf("com.example.alpha.Shape.Circle"), classes)

        val rejected = compile(
            SourceFile.kotlin("Plugin.kt", audited),
            SourceFile.kotlin(
                "Kinds.kt",
                """
                package com.example.alpha

                enum class Mode : Auditable { EASY, HARD { } }

                class Holder {
                    companion object : Auditable
                }

                class Outer {
                    inner class Inner : Auditable
                }

                class Box<T> : Auditable
                """,
            ),
            mainClass = null,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        val carrier = "is enhanced by @AuditSubclasses on AlphaPlugin"
        assertContains(rejected.messages, "Mode $carrier but is an enum class, whose constants the runtime cannot create; use a class or an object")
        assertFalse("EASY $carrier" in rejected.messages || "HARD $carrier" in rejected.messages, "the constants are not reported one by one")
        assertContains(rejected.messages, "Holder.Companion $carrier but is a companion object, which the runtime does not reach; make it a named object")
        assertContains(rejected.messages, "Outer.Inner $carrier but is not a concrete, non-inner class or an object")
        assertContains(rejected.messages, "Box $carrier and must not have type parameters")
    }

    @Test
    fun `rejects subclass enhancements without processor, class or phase`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.VersatiaSubclassEnhancement

                interface Auditable

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("", Auditable::class, Phase.PLUGIN_START_ONLY)
                annotation class NoProcessor

                @Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
                @VersatiaSubclassEnhancement("com.example.test.AuditEnhancementProcessor", Auditable::class, Phase.UNSET)
                annotation class NoPhase

                @NoProcessor @NoPhase
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@VersatiaSubclassEnhancement on NoProcessor has no processorClass")
        assertContains(result.messages, "@VersatiaSubclassEnhancement(\"com.example.test.AuditEnhancementProcessor\") on NoPhase must set enhancementPhase to a phase other than UNSET")
    }
}
