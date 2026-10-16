package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/** Compiles sample plugin sources with the processor and loads what it generated; see docs/enhancements.md. */
@OptIn(ExperimentalCompilerApi::class)
abstract class ProcessorTest {
    @TempDir
    lateinit var tempDir: File

    protected val qualifier = "com.github.marcoral.versatia.di.Qualifier"
    protected val primary = "com.github.marcoral.versatia.di.Primary"

    private val alphaPlugin = SourceFile.kotlin(
        "AlphaPlugin.kt",
        """
        package com.example.alpha

        import com.github.marcoral.versatia.VersatiaPlugin

        class AlphaPlugin : VersatiaPlugin()
        """,
    )

    /**
     * With [mainClass] set, a plugin.yml naming it ([pluginYml]) is passed to the processor, and for
     * the default name `AlphaPlugin` is added to the sources; with null the processor searches the
     * sources. [options] are further processor options.
     */
    protected fun compile(
        vararg sources: SourceFile,
        mainClass: String? = DEFAULT_MAIN,
        pluginYml: String? = mainClass?.let { "name: Alpha\nmain: $it\n" },
        options: Map<String, String> = emptyMap(),
    ): JvmCompilationResult = KotlinCompilation().apply {
        this.sources = sources.toList() + listOfNotNull(alphaPlugin.takeIf { mainClass == DEFAULT_MAIN })
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = System.out
        configureKsp {
            symbolProcessorProviders += VersatiaSymbolProcessorProvider()
            withCompilation = true
            if (pluginYml != null) processorOptions[VersatiaSymbolProcessor.OPTION_PLUGIN_YML] = File(tempDir, "plugin.yml").apply { writeText(pluginYml) }.path
            processorOptions += options
        }
    }.compile()

    protected fun JvmCompilationResult.descriptor(packageName: String = "com.example.alpha") =
        classLoader.loadClass("$packageName.${VersatiaPluginDescriptor.CLASS_SIMPLE_NAME}").getDeclaredField("INSTANCE").get(null) as VersatiaPluginDescriptor

    protected fun VersatiaPluginDescriptor.classTargets() = elements.mapNotNull { it.target as? ClassTarget }.associateBy { it.className }

    private companion object {
        const val DEFAULT_MAIN = "com.example.alpha.AlphaPlugin"
    }
}
