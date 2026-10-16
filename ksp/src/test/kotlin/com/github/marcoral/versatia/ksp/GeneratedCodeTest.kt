package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.event.EventProcessors
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** Sources whose names and shapes the generated code must survive: it has to compile and load, not only read right. */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedCodeTest : ProcessorTest() {
    @Test
    fun `compiles with classes of the plugin's package named like Kotlin's own`() {
        val result = compile(
            SourceFile.kotlin(
                "Arena.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaAttribute
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaAttribute
                annotation class Limits(val low: kotlin.Double, val floor: kotlin.Long, val tags: kotlin.Array<String>)

                @LocalBean
                @Limits(kotlin.Double.NaN, kotlin.Long.MIN_VALUE, [])
                class Map

                class List

                class Int

                class Long

                class Double

                class Any

                annotation class Suppress

                object Prices {
                    @Value("prices")
                    var prices: kotlin.collections.Map<String, kotlin.Int> = emptyMap()

                    @Value("slots")
                    var slots: kotlin.collections.List<kotlin.Int> = emptyList()
                }

                @LocalBean
                class Game(val map: Map)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(2, result.descriptor().classTargets().size)
    }

    @Test
    fun `compiles with keywords as names and beans sharing a simple name`() {
        val result = compile(
            SourceFile.kotlin(
                "Keywords.kt",
                """
                package com.example.alpha.`object`

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @LocalBean @Qualifier("objectConfig")
                class Config

                object Tasks {
                    @Value("value")
                    var value = 0

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun `in`(config: Config) {}
                }
                """,
            ),
            SourceFile.kotlin(
                "Config.kt",
                """
                package com.example.alpha.other

                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Config(val shared: com.example.alpha.`object`.Config)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val descriptor = result.descriptor()
        assertEquals(setOf("com.example.alpha.object.Config", "com.example.alpha.other.Config"), descriptor.classTargets().keys)
        val function = descriptor.elements.map { it.target }.filterIsInstance<FunctionTarget>().single()
        assertEquals("in", function.functionName)
        val config = descriptor.classTargets().getValue("com.example.alpha.object.Config").instantiator.create(result.classLoader, emptyList())
        val other = descriptor.classTargets().getValue("com.example.alpha.other.Config").instantiator.create(result.classLoader, listOf(config))
        assertEquals("com.example.alpha.other.Config", other.javaClass.name)
    }

    @Test
    fun `follows type aliases in dependencies and supertypes`() {
        val result = compile(
            SourceFile.kotlin(
                "Aliases.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.di.LocalBean
                import org.bukkit.event.Listener

                typealias Clocks = Clock

                typealias MyListener = Listener

                @LocalBean
                class Clock

                @LocalBean
                class Shop(val clock: Clocks)

                class Join(val clock: Clocks) : MyListener
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val descriptor = result.descriptor()
        assertEquals("com.example.alpha.Clock", descriptor.classTargets().getValue("com.example.alpha.Shop").constructorParameters.single().type)
        val join = descriptor.elements.single { it.processorClass == EventProcessors.REGISTER_LISTENERS && it.target.className == "com.example.alpha.Join" }
        assertTrue("org.bukkit.event.Listener" in (join.target as ClassTarget).assignableTypes)
    }

    @Test
    fun `splits a large descriptor so that every method stays within the JVM limit`() {
        val functions = (1..400).joinToString("\n") { "    @AutoInvoke(Phase.PLUGIN_START_ONLY) fun task$it() {}" }
        val result = compile(
            SourceFile.kotlin(
                "Tasks.kt",
                """
                |package com.example.alpha
                |
                |import com.github.marcoral.versatia.Phase
                |import com.github.marcoral.versatia.lifecycle.AutoInvoke
                |
                |object Tasks {
                |$functions
                |}
                """.trimMargin(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(400, result.descriptor().elements.count { it.target is FunctionTarget })
    }
}
