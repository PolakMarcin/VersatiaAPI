package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** Locating and validating the plugin's main class. */
@OptIn(ExperimentalCompilerApi::class)
class MainClassTest : ProcessorTest() {
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
    fun `takes the most derived plugin class when an open one is extended`() {
        val result = compile(
            SourceFile.kotlin(
                "Plugins.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.VersatiaPlugin

                open class BasePlugin : VersatiaPlugin()

                class AlphaPlugin : BasePlugin()
                """,
            ),
            mainClass = null,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("com.example.alpha.AlphaPlugin", (result.descriptor().elements.first().target as PluginTarget).className)
    }

    @Test
    fun `reports a missing plugin yml and one without a top-level main entry`() {
        val missing = File(tempDir, "missing.yml")
        val notFound = compile(SourceFile.kotlin("Empty.kt", "package com.example.alpha"), mainClass = null, options = mapOf(VersatiaSymbolProcessor.OPTION_PLUGIN_YML to missing.path))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, notFound.exitCode)
        assertContains(notFound.messages, "plugin.yml not found at ${missing.absolutePath}")

        val nested = compile(SourceFile.kotlin("Empty.kt", "package com.example.alpha"), mainClass = "x", pluginYml = "name: Alpha\ncommands:\n  main: com.example.alpha.AlphaPlugin\n")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, nested.exitCode)
        assertContains(nested.messages, "No top-level `main:` entry in")
    }

    @Test
    fun `rejects a main class Bukkit could not create`() {
        val plugins = SourceFile.kotlin(
            "Plugins.kt",
            """
            package com.example.alpha

            import com.github.marcoral.versatia.VersatiaPlugin

            object Singleton : VersatiaPlugin()

            private class Hidden : VersatiaPlugin()

            class Needy(val size: Int) : VersatiaPlugin()

            class Generic<T> : VersatiaPlugin()

            class Outer {
                inner class Inner : VersatiaPlugin()
            }

            class Plain

            interface Marker
            """,
        )
        val reasons = mapOf(
            "Singleton" to "it is an object, but Bukkit creates the instance of a class itself",
            "Hidden" to "it is private",
            "Needy" to "it has no public constructor without arguments",
            "Generic" to "it has type parameters",
            "Outer.Inner" to "it is an inner class",
            "Plain" to "it does not extend org.bukkit.plugin.java.JavaPlugin",
            "Marker" to "it is an interface",
        )
        for ((name, reason) in reasons) {
            val result = compile(plugins, mainClass = "com.example.alpha.$name")
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, name)
            assertContains(result.messages, "Bukkit cannot load the main class com.example.alpha.$name: $reason")
        }
    }
}
