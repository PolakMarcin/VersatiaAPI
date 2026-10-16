package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** `@FriendlyName`, `@ConditionalOnPlugin` and attributes inherited through custom annotations. */
@OptIn(ExperimentalCompilerApi::class)
class AttributeProcessingTest : ProcessorTest() {
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
        assertContains(result.messages, "@FriendlyName on AlphaPlugin must be one word of letters, digits and the characters _ . + -")
        assertContains(result.messages, "@ConditionalOnPlugin cannot apply to AlphaPlugin, the plugin's main class")
        assertContains(result.messages, "@FriendlyName on Config has no effect: it belongs on the plugin's main class")
        assertContains(result.messages, "@ConditionalOnPlugin on Tools has no effect: Tools is not enhanced and declares no enhanced function or property")
        assertContains(result.messages, "@ConditionalOnPlugin on Tools.plain() has no effect: the function is not enhanced")
        assertContains(result.messages, "@ConditionalOnPlugin on Bridge must name plugins, one word each")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
    }

    @Test
    fun `accepts friendly names commands can type and rejects the others`() {
        fun compileNamed(name: String) = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.FriendlyName
                import com.github.marcoral.versatia.VersatiaPlugin

                @FriendlyName("$name")
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, compileNamed("shop-eu.v2+_beta").exitCode)
        for (name in listOf("shop:v2", "shop/eu", "", "sklepł")) {
            val result = compileNamed(name)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, name)
            assertContains(result.messages, "@FriendlyName on AlphaPlugin must be one word of letters, digits and the characters _ . + -")
        }
    }

    @Test
    fun `rejects ConditionalOnPlugin naming a blank plugin`() {
        val result = compile(
            SourceFile.kotlin(
                "Bridge.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.di.LocalBean

                @ConditionalOnPlugin("") @LocalBean
                class Bridge
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@ConditionalOnPlugin on Bridge must name plugins, one word each")
    }

    @Test
    fun `Qualifier and Primary count through custom annotations`() {
        val result = compile(
            SourceFile.kotlin(
                "Engines.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier

                @Qualifier("fast")
                @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
                annotation class Fast

                @Primary
                annotation class Preferred

                @Preferred
                annotation class Default

                interface Engine

                @Fast @LocalBean
                class FastEngine : Engine

                @Default @LocalBean
                class SteadyEngine : Engine

                @LocalBean
                class SlowEngine : Engine

                @LocalBean
                class Car(@Fast val engine: Engine)

                @LocalBean
                class Bus(val engine: Engine)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val classes = result.descriptor().classTargets()
        val fast = AnnotationOccurrence(qualifier, mapOf("value" to "fast"))
        assertEquals(listOf(AnnotationOccurrence("com.example.alpha.Fast"), fast), classes.getValue("com.example.alpha.FastEngine").annotations)
        assertEquals(listOf(AnnotationOccurrence("com.example.alpha.Default"), AnnotationOccurrence(primary)), classes.getValue("com.example.alpha.SteadyEngine").annotations)
        assertTrue(fast in classes.getValue("com.example.alpha.Car").constructorParameters.single().annotations)

        val rejected = compile(
            SourceFile.kotlin(
                "Engines.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier

                @Qualifier("fast")
                @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
                annotation class Fast

                @Primary
                annotation class Preferred

                interface Engine

                class Wheel

                @Fast @LocalBean
                class FastEngine : Engine

                @LocalBean
                class SlowEngine : Engine

                @LocalBean
                class Car(@Fast val wheel: Wheel)

                @LocalBean
                class Bus(val engine: Engine)

                @Preferred
                class Garage
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(rejected.messages, "Bean \"fast\" (com.example.alpha.FastEngine) does not provide com.example.alpha.Wheel")
        assertContains(rejected.messages, "Add @Qualifier at the injection point or mark one bean @Primary")
        assertContains(rejected.messages, "@Primary on Garage has no effect: the class is not a bean")
    }

    @Test
    fun `rejects an attribute reaching an element twice unless it is repeatable`() {
        val result = compile(
            SourceFile.kotlin(
                "Engines.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary
                import com.github.marcoral.versatia.di.Qualifier

                @Qualifier("fast")
                @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
                annotation class Fast

                @Qualifier("slow")
                @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
                annotation class Slow

                @Primary
                annotation class Preferred

                interface Engine

                @Qualifier("turbo") @Fast @LocalBean
                class TurboEngine : Engine

                @Fast @Slow @LocalBean
                class OddEngine : Engine

                @Preferred @Primary @LocalBean
                class SteadyEngine : Engine

                @LocalBean
                class Car(@Fast @Qualifier("turbo") val engine: Engine)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "TurboEngine has @Qualifier more than once: written directly and inherited from @Fast; keep one")
        assertContains(result.messages, "OddEngine has @Qualifier more than once: inherited from @Fast and inherited from @Slow; keep one")
        assertContains(result.messages, "SteadyEngine has @Primary more than once: inherited from @Preferred and written directly; keep one")
        assertContains(result.messages, "Parameter engine has @Qualifier more than once: inherited from @Fast and written directly; keep one")

        val repeated = compile(
            SourceFile.kotlin(
                "Bridge.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.di.LocalBean

                @ConditionalOnPlugin("Vault")
                annotation class NeedsVault

                @NeedsVault @ConditionalOnPlugin("CommandAPI") @LocalBean
                class Bridge
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, repeated.exitCode, repeated.messages)
        val conditional = "com.github.marcoral.versatia.ConditionalOnPlugin"
        assertEquals(
            listOf(
                AnnotationOccurrence("com.example.alpha.NeedsVault"),
                AnnotationOccurrence(conditional, mapOf("value" to listOf("Vault"))),
                AnnotationOccurrence(conditional, mapOf("value" to listOf("CommandAPI"))),
            ),
            repeated.descriptor().classTargets().getValue("com.example.alpha.Bridge").annotations,
        )
    }

    @Test
    fun `FriendlyName counts through a custom annotation and is checked where it is written`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugin.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.FriendlyName
                import com.github.marcoral.versatia.VersatiaPlugin

                @FriendlyName("shop")
                annotation class ShopName

                @ShopName
                class AlphaPlugin : VersatiaPlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val plugin = assertIs<PluginTarget>(result.descriptor().elements.first().target)
        assertEquals(
            listOf(AnnotationOccurrence("com.example.alpha.ShopName"), AnnotationOccurrence("com.github.marcoral.versatia.FriendlyName", mapOf("value" to "shop"))),
            plugin.annotations,
        )

        val rejected = compile(
            SourceFile.kotlin(
                "Names.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.FriendlyName

                @FriendlyName("shop")
                annotation class ShopName

                @FriendlyName("two words")
                annotation class BadName

                @ShopName
                class Catalogue
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(rejected.messages, "@FriendlyName on Catalogue has no effect: it belongs on the plugin's main class")
        assertContains(rejected.messages, "@FriendlyName on BadName must be one word of letters, digits and the characters _ . + -")
    }
}
