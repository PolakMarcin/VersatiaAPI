package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
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

/** Enhanced functions such as `@AutoInvoke`. */
@OptIn(ExperimentalCompilerApi::class)
class FunctionProcessingTest : ProcessorTest() {
    @Test
    fun `describes AutoInvoke functions of the plugin, objects and beans`() {
        val result = compile(
            SourceFile.kotlin(
                "Invoked.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                @LocalBean
                class Clock

                class AlphaPlugin : VersatiaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
                    fun announce() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    private fun warmUp(clock: Clock) {}
                }

                object Registry {
                    @AutoInvoke(Phase.PLUGIN_STOP)
                    fun flush() {}
                }

                @LocalBean
                class Banner(val clock: Clock) {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
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
        assertEquals(Phase.PLUGIN_RELOAD_OR_START, phases["announce"])
        assertEquals(Phase.PLUGIN_STOP, phases["flush"])
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

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                class AlphaPlugin : VersatiaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun generic(value: List<String>) {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun String.extension() {}
                }

                class Plain {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun nobody() {}
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Injected parameter value of AlphaPlugin.generic() must not be generic")
        assertContains(result.messages, "AlphaPlugin.extension must not be an extension function to be enhanced")
        assertContains(result.messages, "Plain.nobody cannot be enhanced: Plain is neither the plugin's main class, an object nor a bean")
        assertContains(result.messages, "BadInvoked.kt:")
    }

    @Test
    fun `rejects every other function the runtime could not call`() {
        val result = compile(
            SourceFile.kotlin(
                "Unusable.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaEnhancement
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Qualifier
                import com.github.marcoral.versatia.lifecycle.AutoInvoke
                import org.bukkit.plugin.java.JavaPlugin

                @Target(AnnotationTarget.FUNCTION)
                @Retention(AnnotationRetention.BINARY)
                @VersatiaEnhancement("com.example.test.TraceEnhancementProcessor", enhancementPhase = Phase.PLUGIN_STOP)
                annotation class Traced

                @LocalBean
                class Clock

                @AutoInvoke(Phase.PLUGIN_START_ONLY)
                fun start() {}

                @ConditionalOnPlugin("Vault")
                @AutoInvoke(Phase.PLUGIN_START_ONLY)
                fun conditional() {}

                abstract class BasePlugin : VersatiaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    abstract fun template()
                }

                class Main : BasePlugin() {
                    override fun template() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    suspend fun later() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun <T> generic() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    @Traced
                    fun twice() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun many(vararg clocks: Clock) {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun typed(callback: () -> Unit) {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun <T : Any> bound(value: T) {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun blank(@Qualifier(" ") clock: Clock) {}

                    companion object {
                        @AutoInvoke(Phase.PLUGIN_START_ONLY)
                        fun inCompanion() {}
                    }
                }

                class Other : JavaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun notMain() {}
                }
                """,
            ),
            mainClass = "com.example.alpha.Main",
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "start() cannot be enhanced: it is a top-level function; move it into the plugin's main class, an object or a bean")
        assertContains(result.messages, "conditional() cannot be enhanced: it is a top-level function")
        assertFalse("@ConditionalOnPlugin on conditional() has no effect" in result.messages, "the function is enhanced, only misplaced")
        assertContains(result.messages, "BasePlugin.template must not be abstract to be enhanced")
        assertContains(result.messages, "Main.later must not be suspend to be enhanced")
        assertContains(result.messages, "Main.generic must not have type parameters to be enhanced")
        assertContains(result.messages, "Main.twice carries more than one enhancement: AutoInvoke, Traced")
        assertContains(result.messages, "Injected parameter clocks of Main.many() must not be vararg")
        assertContains(result.messages, "Injected parameter callback of Main.typed() must not be a function type")
        assertContains(result.messages, "Main.bound must not have type parameters to be enhanced")
        assertContains(result.messages, "@Qualifier on parameter clock of Main.blank() must not be blank")
        assertContains(result.messages, "Main.Companion.inCompanion cannot be enhanced: Main.Companion is a companion object, which the runtime does not reach")
        assertContains(result.messages, "Other.notMain cannot be enhanced: Other extends JavaPlugin but is neither the plugin's main class nor a base class of it")
        assertContains(result.messages, "Unusable.kt:")
        assertTrue(result.sourcesGeneratedBySymbolProcessor.none(), "no descriptor must be generated")
    }

    @Test
    fun `calls functions of a base class and private functions by their JVM names`() {
        val result = compile(
            SourceFile.kotlin(
                "Tasks.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                abstract class BasePlugin : VersatiaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    fun baseStart() {}
                }

                class AlphaPlugin : BasePlugin()

                private object Tasks {
                    val calls = mutableListOf<String>()

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    internal fun start(any: Any) { calls += "start ${'$'}any" }

                    @AutoInvoke(Phase.PLUGIN_STOP)
                    @JvmName("finish")
                    fun stop() { calls += "stop" }
                }
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val functions = result.descriptor().elements.map { it.target }.filterIsInstance<FunctionTarget>().associateBy { it.functionName }
        assertEquals(MemberOwner.PLUGIN, functions.getValue("baseStart").owner, "a base class of the main class belongs to the plugin")
        val start = assertIs<Invoker.Reflective>(functions.getValue("start").invoker)
        assertEquals(listOf("java.lang.Object"), start.parameterBinaryClassNames)
        assertEquals("finish", assertIs<Invoker.Reflective>(functions.getValue("stop").invoker).methodName)

        val tasks = result.classLoader.loadClass("com.example.alpha.Tasks").getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        start.invoke(result.classLoader, tasks, listOf("now"))
        functions.getValue("stop").invoker.invoke(result.classLoader, tasks, emptyList())
        assertEquals(listOf("start now", "stop"), tasks.javaClass.getDeclaredMethod("getCalls").apply { isAccessible = true }.invoke(tasks))
    }

    @Test
    fun `rejects an enhanced member that overrides an enhanced member`() {
        val result = compile(
            SourceFile.kotlin(
                "Overrides.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.Phase
                import com.github.marcoral.versatia.VersatiaPlugin
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.lifecycle.AutoInvoke

                abstract class BasePlugin : VersatiaPlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    open fun start() {}

                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    open fun stop() {}

                    @Value("limit")
                    open var limit = 0
                }

                abstract class MiddlePlugin : BasePlugin() {
                    override fun stop() {}
                }

                class Main : MiddlePlugin() {
                    @AutoInvoke(Phase.PLUGIN_START_ONLY)
                    override fun start() {}

                    @AutoInvoke(Phase.PLUGIN_STOP)
                    override fun stop() {}

                    @Value("limit")
                    override var limit = 1
                }
                """,
            ),
            mainClass = "com.example.alpha.Main",
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Main.start overrides BasePlugin.start, which is enhanced too, so the override would run for both; keep the enhancement on one of them")
        assertContains(result.messages, "Main.stop overrides BasePlugin.stop, which is enhanced too")
        assertContains(result.messages, "Main.limit overrides BasePlugin.limit, which is enhanced too")
    }
}
