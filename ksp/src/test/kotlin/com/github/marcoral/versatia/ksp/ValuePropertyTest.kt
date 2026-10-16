package com.github.marcoral.versatia.ksp

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.Setter
import com.github.marcoral.versatia.engine.descriptor.TypeReference
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/** `@Value` properties. */
@OptIn(ExperimentalCompilerApi::class)
class ValuePropertyTest : ProcessorTest() {
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

    @Test
    fun `rejects Value keys and files the runtime could not read`() {
        val result = compile(
            SourceFile.kotlin(
                "Paths.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.config.Value

                object Settings {
                    @Value("") var empty = 0
                    @Value("shop..tax") var gap = 0
                    @Value(" padded") var padded = 0
                    @Value("a", file = "") var noFile = 0
                    @Value("a", file = "/etc/shop.yml") var absolute = 0
                    @Value("a", file = "C:/server/shop.yml") var drive = 0
                    @Value("a", file = "../Other/config.yml") var escape = 0
                    @Value("a", file = "config.json") var json = 0
                    @Value("a", file = "config.yaml ") var trailing = 0
                }
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@Value on Settings.empty: the key must not be blank")
        assertContains(result.messages, "@Value on Settings.gap: the key \"shop..tax\" has an empty segment")
        assertContains(result.messages, "@Value on Settings.padded: the key \" padded\" must not start or end with whitespace")
        assertContains(result.messages, "@Value on Settings.noFile: the file must not be blank")
        assertContains(result.messages, "@Value on Settings.absolute: the file \"/etc/shop.yml\" must be a path relative to the data folder")
        assertContains(result.messages, "@Value on Settings.drive: the file \"C:/server/shop.yml\" must be a path relative to the data folder")
        assertContains(result.messages, "@Value on Settings.escape: the file \"../Other/config.yml\" must stay inside the data folder")
        assertContains(result.messages, "@Value on Settings.json: the file \"config.json\" must be a YAML file ending in .yml or .yaml")
        assertContains(result.messages, "@Value on Settings.trailing: the file \"config.yaml \" must not start or end with whitespace")
    }

    @Test
    fun `accepts every type ValueTypes lists, also through a type alias`() {
        val result = compile(
            SourceFile.kotlin(
                "Types.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.config.Value
                import org.bukkit.Color
                import org.bukkit.Location
                import org.bukkit.OfflinePlayer
                import org.bukkit.configuration.ConfigurationSection
                import org.bukkit.inventory.ItemStack
                import org.bukkit.util.Vector

                enum class Mode { EASY, HARD }

                typealias Slots = List<Int>

                object Settings {
                    @Value("flag") var flag = false
                    @Value("ratio") var ratio = 0f
                    @Value("precise") var precise = 0.0
                    @Value("small") var small = 0u
                    @Value("large") var large = 0uL
                    @Value("modes") var modes: Set<Mode> = emptySet()
                    @Value("prices") var prices: Map<String, Int> = emptyMap()
                    @Value("slots") var slots: Slots = emptyList()
                    @Value("color") var color: Color? = null
                    @Value("spawn") var spawn: Location? = null
                    @Value("owner") var owner: OfflinePlayer? = null
                    @Value("push") var push: Vector? = null
                    @Value("reward", file = "data/rewards.yaml") var reward: ItemStack? = null
                    @Value("raw") var raw: ConfigurationSection? = null
                }
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val types = result.descriptor().elements.map { it.target }.filterIsInstance<PropertyTarget>().associate { it.propertyName to it.type }
        assertEquals(TypeReference("kotlin.collections.List", listOf(TypeReference("kotlin.Int"))), types["slots"], "the alias is recorded as what it stands for")
        assertEquals(TypeReference("kotlin.collections.Set", listOf(TypeReference("com.example.alpha.Mode"))), types["modes"])
        assertEquals(14, types.size)
    }

    @Test
    fun `rejects elements and held values it cannot know or read`() {
        val result = compile(
            SourceFile.kotlin(
                "Unknown.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.config.PreviousValueTracking
                import com.github.marcoral.versatia.config.Value
                import org.bukkit.Location

                object Settings {
                    @Value("spawns") var spawns: List<Location> = emptyList()
                    @Value("anything") var anything: List<*> = emptyList<Any>()
                    @Value("held") val held: PreviousValueTracking<*> = PreviousValueTracking<Int>()
                }
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "@Value on Settings.spawns: the elements of List<Location> must be scalars or enums, not Location")
        assertContains(result.messages, "@Value on Settings.anything: the elements of List<*> are not known")
        assertContains(result.messages, "@Value on Settings.held: the value held by PreviousValueTracking<*> is not known")
    }

    @Test
    fun `accepts the mappers the runtime can create and no others`() {
        val mappers = """
            package com.example.alpha

            import com.github.marcoral.versatia.config.ValueMapper
            import com.github.marcoral.versatia.util.config.getIntOrThrow
            import com.github.marcoral.versatia.util.config.getLongOrThrow
            import org.bukkit.configuration.ConfigurationSection

            abstract class Scaled<T> : ValueMapper<T>

            object Ticks : Scaled<Long>() {
                override fun map(section: ConfigurationSection, key: String) = section.getLongOrThrow(key) * 20
            }

            class Holder {
                companion object : ValueMapper<Int> {
                    override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key)
                }
            }

            class Explicit : ValueMapper<Int> {
                constructor()
                override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key)
            }

            class Secondary : ValueMapper<Int> {
                constructor(factor: Int = 2)
                override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key)
            }

            class Outer {
                inner class Inner : ValueMapper<Int> {
                    override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key)
                }
            }

            sealed class Sealed : ValueMapper<Int> {
                override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key)
            }
        """
        val accepted = compile(
            SourceFile.kotlin("Mappers.kt", mappers),
            SourceFile.kotlin(
                "Mapped.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.config.Value

                object Settings {
                    @Value("restock", mapper = Ticks::class) var restock = 0L
                    @Value("held", mapper = Holder.Companion::class) var held = 0
                    @Value("explicit", mapper = Explicit::class) var explicit = 0
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, accepted.exitCode, accepted.messages)

        val rejected = compile(
            SourceFile.kotlin("Mappers.kt", mappers),
            SourceFile.kotlin(
                "BadMapped.kt",
                """
                package com.example.alpha

                import com.github.marcoral.versatia.config.Value

                object Settings {
                    @Value("secondary", mapper = Secondary::class) var secondary = 0
                    @Value("inner", mapper = Outer.Inner::class) var inner = 0
                    @Value("sealed", mapper = Sealed::class) var sealed = 0
                    @Value("ticks", mapper = Ticks::class) var ticks = 0
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, rejected.exitCode)
        assertContains(rejected.messages, "@Value on Settings.secondary: mapper Secondary must be an object or a class with a no-argument constructor")
        assertContains(rejected.messages, "@Value on Settings.inner: mapper Inner must be an object or a class with a no-argument constructor")
        assertContains(rejected.messages, "@Value on Settings.sealed: mapper Sealed must be an object or a class with a no-argument constructor")
        assertContains(rejected.messages, "@Value on Settings.ticks: mapper Ticks maps to Long, which Int cannot hold")
    }
}
