package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.DefaultArgument
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.Setter
import com.github.marcoral.versatia.engine.descriptor.TypeReference
import com.github.marcoral.versatia.util.type.Percent
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** `@Value` constructor properties, optional constructor parameters and inherited `@ConditionalOnPlugin`. */
@OptIn(ExperimentalCompilerApi::class)
class ConstructorParameterTest : ProcessorTest() {
    private fun source(code: String) = SourceFile.kotlin("Sample.kt", "package com.example.alpha\n\n$code")

    private fun JvmCompilationResult.assertCleanCompile() {
        assertEquals(KotlinCompilation.ExitCode.OK, exitCode, messages)
        assertTrue(messages.lines().none { it.startsWith("w:") }, messages)
    }

    @Test
    fun `records Value constructor properties as supplied parameters`() {
        val result = compile(
            source(
                """
                import com.github.marcoral.versatia.config.PreviousValueTracking
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Clock

                @LocalBean
                class Shop(
                    val clock: Clock,
                    @Value("shop.slots") var slots: List<Int>,
                    @Value("shop.tax") val tax: PreviousValueTracking<Int>,
                    @Value("shop.motd") private var motd: String?,
                ) {
                    @Value("shop.open")
                    var open = false

                    fun motd() = motd
                }
                """,
            ),
        )

        result.assertCleanCompile()
        val descriptor = result.descriptor()
        val shop = descriptor.classTargets().getValue("com.example.alpha.Shop")
        val supplied = ConfigProcessors.VALUE
        assertEquals(
            listOf(
                Parameter("com.example.alpha.Clock"),
                Parameter("kotlin.collections.List", suppliedBy = supplied),
                Parameter("com.github.marcoral.versatia.config.PreviousValueTracking", suppliedBy = supplied),
                Parameter("kotlin.String", suppliedBy = supplied),
            ),
            shop.constructorParameters,
        )
        assertIs<Instantiator.Direct>(shop.instantiator)
        val properties = descriptor.elements.map { it.target }.filterIsInstance<PropertyTarget>().associateBy { it.propertyName }
        assertEquals(1, properties.getValue("slots").constructorParameter)
        assertEquals(2, properties.getValue("tax").constructorParameter)
        assertEquals(3, properties.getValue("motd").constructorParameter)
        assertNull(properties.getValue("open").constructorParameter)
        assertEquals(TypeReference("kotlin.String", nullable = true), properties.getValue("motd").type)
        assertEquals(Setter.Reflective("com.example.alpha.Shop", "motd"), properties.getValue("motd").setter)

        val clock = result.classLoader.loadClass("com.example.alpha.Clock").getDeclaredConstructor().newInstance()
        val tracking = result.classLoader.loadClass("com.github.marcoral.versatia.config.PreviousValueTracking").getDeclaredConstructor().newInstance()
        val instance = shop.instantiator.create(result.classLoader, listOf(clock, listOf(1, 2), tracking, "hi"))
        assertEquals("hi", instance.javaClass.getMethod("motd").invoke(instance))
        properties.getValue("tax").setter.set(result.classLoader, instance, 7)
        assertEquals("PreviousValueTracking(7, previous=none)", tracking.toString(), "the constructor's cell is the one a reload fills")
    }

    @Test
    fun `a Value constructor property is supplied whether the annotation targets the parameter or the property`() {
        val result = compile(
            source(
                """
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean

                @LocalBean
                class Shop(@Value("shop.slots") var slots: Int, @property:Value("shop.tax") var tax: Int)
                """,
            ),
        )

        result.assertCleanCompile()
        val descriptor = result.descriptor()
        val supplied = ConfigProcessors.VALUE
        assertEquals(
            listOf(Parameter("kotlin.Int", suppliedBy = supplied), Parameter("kotlin.Int", suppliedBy = supplied)),
            descriptor.classTargets().getValue("com.example.alpha.Shop").constructorParameters,
        )
        val properties = descriptor.elements.map { it.target }.filterIsInstance<PropertyTarget>().associateBy { it.propertyName }
        assertEquals(0, properties.getValue("slots").constructorParameter)
        assertEquals(1, properties.getValue("tax").constructorParameter)
    }

    @Test
    fun `a private bean receives value classes through its constructor`() {
        val result = compile(
            source(
                """
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.util.type.Percent

                @LocalBean
                private class Odds(@Value("odds.win") var win: Percent, @Value("odds.loss") var loss: Percent?) {
                    override fun toString() = "${'$'}win/${'$'}loss"
                }

                @LocalBean
                private class Bet(@Value("bet.stake") var stake: Percent, val label: String = "bet") {
                    override fun toString() = "${'$'}label ${'$'}stake"
                }
                """,
            ),
        )

        result.assertCleanCompile()
        val odds = result.descriptor().classTargets().getValue("com.example.alpha.Odds")
        assertIs<Instantiator.Reflective>(odds.instantiator)
        val instance = odds.instantiator.create(result.classLoader, listOf(Percent.of(5.0), Percent.of(2.5)))
        assertEquals("5.0%/2.5%", instance.toString())
        val bet = result.descriptor().classTargets().getValue("com.example.alpha.Bet")
        assertIs<Instantiator.WithDefaults>(bet.instantiator)
        assertEquals("bet 1.0%", bet.instantiator.create(result.classLoader, listOf(Percent.of(1.0), DefaultArgument)).toString())
    }

    @Test
    fun `rejects Value parameters the runtime could not fill`() {
        val result = compile(
            source(
                """
                import com.github.marcoral.versatia.config.Value
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.lifecycle.AutoInvoke
                import com.github.marcoral.versatia.EventType
                import java.util.Date

                @LocalBean
                class Shop(@Value("a") val fixed: Int, @Value("b") plain: Int, @Value("c") var date: Date) {
                    @AutoInvoke(EventType.PLUGIN_START_ONLY)
                    fun start(@Value("d") count: Int) = Unit
                }

                class Loose(@Value("e") var loose: Int)
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Shop.fixed must be a var to be enhanced")
        assertContains(result.messages, "Shop.plain must be a var to be enhanced; declare the constructor parameter as a property")
        assertContains(result.messages, "@Value on Shop.date: Date is not a supported type")
        assertContains(result.messages, "Parameter count of Shop.start() cannot be enhanced; only properties can, constructor properties included")
        assertContains(result.messages, "Loose.loose cannot be enhanced: Loose is neither the plugin's main class, an object nor a bean")
    }

    @Test
    fun `a constructor parameter with a default value is an optional dependency`() {
        val result = compile(
            source(
                """
                import com.github.marcoral.versatia.di.LocalBean

                class Clock(val zone: String = "UTC")

                @LocalBean
                class Shop(val clock: Clock = Clock(), val name: String = "shop")

                @LocalBean
                class Greeter

                @LocalBean
                class Desk(val greeter: Greeter = Greeter(), val clock: Clock = Clock("CET"))
                """,
            ),
        )

        result.assertCleanCompile()
        val classes = result.descriptor().classTargets()
        val shop = classes.getValue("com.example.alpha.Shop")
        assertEquals(listOf(Parameter("com.example.alpha.Clock", optional = true), Parameter("kotlin.String", optional = true)), shop.constructorParameters)
        assertEquals(Instantiator.WithDefaults("com.example.alpha.Shop", listOf("com.example.alpha.Clock", "java.lang.String")), shop.instantiator)
        assertIs<Instantiator.Direct>(classes.getValue("com.example.alpha.Greeter").instantiator)

        val instance = shop.instantiator.create(result.classLoader, listOf(DefaultArgument, "market"))
        assertEquals("market", instance.javaClass.getMethod("getName").invoke(instance))
        val clock = instance.javaClass.getMethod("getClock").invoke(instance)
        assertEquals("UTC", clock.javaClass.getMethod("getZone").invoke(clock))
    }

    @Test
    fun `ConditionalOnPlugin counts through a meta-annotation`() {
        val accepted = compile(
            source(
                """
                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.di.LocalBean
                import com.github.marcoral.versatia.di.Primary

                @ConditionalOnPlugin("Vault")
                annotation class NeedsVault

                @NeedsVault
                annotation class NeedsVaultToo

                interface Economy

                @NeedsVaultToo @Primary @LocalBean
                class VaultEconomy : Economy

                @LocalBean
                class FreeEconomy : Economy

                @LocalBean
                class Shop(val economy: Economy)

                @NeedsVault @LocalBean
                class Bank(val economy: VaultEconomy)
                """,
            ),
        )

        accepted.assertCleanCompile()
        val conditional = AnnotationOccurrence("com.github.marcoral.versatia.ConditionalOnPlugin", mapOf("value" to listOf("Vault")))
        val classes = accepted.descriptor().classTargets()
        assertEquals(
            listOf(AnnotationOccurrence("com.example.alpha.NeedsVaultToo"), conditional, AnnotationOccurrence("com.github.marcoral.versatia.di.Primary")),
            classes.getValue("com.example.alpha.VaultEconomy").annotations,
        )
        assertTrue(conditional in classes.getValue("com.example.alpha.Bank").annotations)

        val rejected = compile(
            source(
                """
                import com.github.marcoral.versatia.ConditionalOnPlugin
                import com.github.marcoral.versatia.di.LocalBean

                @ConditionalOnPlugin("Vault")
                annotation class NeedsVault

                @NeedsVault @LocalBean
                class VaultEconomy

                @LocalBean
                class Shop(val economy: VaultEconomy)
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(
            rejected.messages,
            "com.example.alpha.Shop depends on com.example.alpha.VaultEconomy, which exists only with @ConditionalOnPlugin(\"Vault\"); " +
                "make com.example.alpha.Shop conditional on the same plugins or declare a provider without the condition",
        )
    }
}
