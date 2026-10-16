package com.github.marcoral.versatia.util.config

import com.github.marcoral.versatia.util.type.Percent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.Color
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.util.Vector

private enum class Difficulty { EASY, HARD }

class ConfigurationExtensionsTest {
    private val config = YamlConfiguration().apply {
        loadFromString(
            """
            flag: true
            count: 5
            negative: -1
            big: 3000000000
            ratio: 2
            text: hello
            chance: 12.5%
            badChance: 12.5
            difficulty: hard
            badDifficulty: medium
            difficulties: [easy, HARD]
            amounts: [1, 2]
            badAmounts: [1, -2]
            nested:
              inner:
                value: 1
            strings:
              a: 1
              b: two
            """.trimIndent(),
        )
    }

    @Test
    fun `OrNull returns null only when absent and throws on invalid values`() {
        assertNull(config.getIntOrNull("missing"))
        assertEquals(5, config.getIntOrNull("count"))
        val exception = assertFailsWith<ConfigurationValueException> { config.getIntOrNull("text") }
        assertEquals("Value at \"text\" is not an int: \"hello\"", exception.message)
        assertFailsWith<ConfigurationValueException> { config.getIntOrNull("big") }
        assertEquals(3_000_000_000L, config.getLongOrNull("big"))
        assertEquals(5L, config.getLongOrNull("count"))
        assertEquals(2.0, config.getDoubleOrNull("ratio"))
        assertEquals(true, config.getBooleanOrNull("flag"))
    }

    @Test
    fun `unsigned getters reject negative numbers instead of wrapping`() {
        assertEquals(5u, config.getUIntOrNull("count"))
        assertEquals(5uL, config.getULongOrNull("count"))
        assertFailsWith<ConfigurationValueException> { config.getUIntOrNull("negative") }
        assertFalse(config.isUInt("negative"))
        assertTrue(config.isUInt("count"))
        assertEquals(7u, config.getUInt("missing", 7u))
        assertEquals(listOf(1u, 2u), config.getUIntList("amounts"))
        val exception = assertFailsWith<ConfigurationValueException> { config.getUIntList("badAmounts") }
        assertEquals("Element 1 of list at \"badAmounts\" is not an integer from 0 to 4294967295: \"-2\"", exception.message)
    }

    @Test
    fun `OrThrow names the full path of missing keys`() {
        val exception = assertFailsWith<ConfigurationValueException> {
            config.getConfigurationSectionOrThrow("nested").getConfigurationSectionOrThrow("inner").getIntOrThrow("missing")
        }
        assertEquals("No value present at \"nested.inner.missing\"", exception.message)
        assertEquals("custom", assertFailsWith<ConfigurationValueException> { config.getIntOrThrow("missing", "custom") }.message)
        assertEquals("custom", assertFailsWith<ConfigurationValueException> { config.getIntOrThrow("text", "custom") }.message)
    }

    @Test
    fun `percent values must carry a percent sign`() {
        assertEquals(Percent.of(12.5), config.getPercentOrNull("chance"))
        assertFailsWith<ConfigurationValueException> { config.getPercentOrNull("badChance") }
        assertTrue(config.isPercent("chance"))
        assertFalse(config.isPercent("badChance"))
        assertEquals(Percent.ZERO, config.getPercent("missing", Percent.ZERO))
    }

    @Test
    fun `enum values are matched ignoring case and typos are reported`() {
        assertEquals(Difficulty.HARD, config.getEnumElementOrNull<Difficulty>("difficulty"))
        assertEquals(Difficulty.EASY, config.getEnumElement("missing", Difficulty.EASY))
        assertEquals(listOf(Difficulty.EASY, Difficulty.HARD), config.getEnumElementList<Difficulty>("difficulties"))
        val exception = assertFailsWith<ConfigurationValueException> { config.getEnumElementOrThrow<Difficulty>("badDifficulty") }
        assertEquals("Value at \"badDifficulty\" is not one of EASY, HARD: \"medium\"", exception.message)
        assertFailsWith<ConfigurationValueException> { config.getEnumElementOrNull<Difficulty>("badDifficulty") }
    }

    @Test
    fun `ifPresent runs only for present keys and passes the parsed value`() {
        var seen: Difficulty? = null
        config.ifEnumElementPresent<Difficulty>("difficulty") { seen = it }
        assertEquals(Difficulty.HARD, seen)

        var called = false
        config.ifIntPresent("missing") { called = true }
        config.ifStringPresent("missing") { called = true }
        assertFalse(called)

        config.ifStringPresent("text") { assertEquals("hello", it) }
        assertFailsWith<ConfigurationValueException> { config.ifIntPresent("text") {} }
    }

    @Test
    fun `structure helpers reject sections where scalars are expected and vice versa`() {
        assertEquals(mapOf("a" to "1", "b" to "two"), config.getConfigurationSectionOrThrow("strings").asStringsMap())
        assertFailsWith<ConfigurationValueException> { config.asStringsMap() }
        assertEquals(listOf("inner"), config.getConfigurationSectionOrThrow("nested").mapChildren { key, _ -> key })
        assertFailsWith<ConfigurationValueException> { config.getConfigurationSectionOrThrow("strings").mapChildren { key, _ -> key } }
        assertEquals(
            "Value at \"text\" is not a section: \"hello\"",
            assertFailsWith<ConfigurationValueException> { config.getConfigurationSectionOrThrow("text") }.message,
        )
    }

    @Test
    fun `generic getOrThrow builds messages from the expected type`() {
        val exception = assertFailsWith<ConfigurationValueException> {
            config.getOrThrow("text", "a hex color") { key -> config.getString(key)?.takeIf { it.startsWith('#') } }
        }
        assertEquals("Value at \"text\" is not a hex color: \"hello\"", exception.message)
    }

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text.trimIndent()) }

    @Test
    fun `list getters reject a single value instead of reading it as an empty list`() {
        val lists = yaml("amounts: 5\nworlds: world\nsection:\n  a: 1")
        assertEquals("Value at \"amounts\" is not a list: \"5\"", assertFailsWith<ConfigurationValueException> { lists.getUIntList("amounts") }.message)
        assertFailsWith<ConfigurationValueException> { lists.getStringListOrNull("worlds") }
        assertFailsWith<ConfigurationValueException> { lists.getIntListOrThrow("section") }
        assertFailsWith<ConfigurationValueException> { lists.getPercentList("amounts") }
        assertFailsWith<ConfigurationValueException> { lists.getEnumElementList<Difficulty>("worlds") }
        assertEquals(emptyList(), lists.getUIntList("missing"))
        assertNull(lists.getBooleanListOrNull("missing"))
        assertEquals("No value present at \"missing\"", assertFailsWith<ConfigurationValueException> { lists.getLongListOrThrow("missing") }.message)
    }

    @Test
    fun `list getters report the real index and value of a mixed or out-of-range element`() {
        val lists = yaml(
            """
            mixed: [1, x, 2]
            decimals: [1, 2.7]
            bytes: [1, 300]
            uints: [1, 4294967297]
            ints: [1, 3000000000]
            nested: [a, {b: 1}]
            withNull: [true, ~]
            """,
        )
        assertEquals("Element 1 of list at \"mixed\" is not an int: \"x\"", assertFailsWith<ConfigurationValueException> { lists.getIntListOrNull("mixed") }.message)
        assertEquals("Element 1 of list at \"decimals\" is not an int: \"2.7\"", assertFailsWith<ConfigurationValueException> { lists.getIntListOrThrow("decimals") }.message)
        assertEquals(
            "Element 1 of list at \"bytes\" is not an integer from 0 to 255: \"300\"",
            assertFailsWith<ConfigurationValueException> { lists.getUByteList("bytes") }.message,
        )
        assertEquals(
            "Element 1 of list at \"uints\" is not an integer from 0 to 4294967295: \"4294967297\"",
            assertFailsWith<ConfigurationValueException> { lists.getUIntList("uints") }.message,
        )
        assertEquals(
            "Element 1 of list at \"ints\" is not an int: \"3000000000\"",
            assertFailsWith<ConfigurationValueException> { lists.getIntListOrNull("ints") }.message,
        )
        assertEquals(listOf(1L, 3_000_000_000L), lists.getLongListOrThrow("ints"))
        assertEquals(listOf(1.0, 2.7), lists.getDoubleListOrNull("decimals"))
        assertEquals("Element 1 of list at \"nested\" is not a string: \"{b=1}\"", assertFailsWith<ConfigurationValueException> { lists.getStringListOrNull("nested") }.message)
        assertEquals("Element 1 of list at \"withNull\" is not a boolean: \"null\"", assertFailsWith<ConfigurationValueException> { lists.getBooleanListOrNull("withNull") }.message)
        assertEquals(listOf("1", "x", "2"), lists.getStringListOrThrow("mixed"))
    }

    @Test
    fun `unsigned getters accept their full range`() {
        val numbers = yaml("uint: 4294967295\nuintOver: 4294967296\nulong: 18446744073709551615\nulongOver: 18446744073709551616\nulongs: [0, 18446744073709551615]")
        assertEquals(UInt.MAX_VALUE, numbers.getUIntOrThrow("uint"))
        assertEquals(
            "Value at \"uintOver\" is not an integer from 0 to 4294967295: \"4294967296\"",
            assertFailsWith<ConfigurationValueException> { numbers.getUIntOrNull("uintOver") }.message,
        )
        assertEquals(ULong.MAX_VALUE, numbers.getULongOrThrow("ulong"))
        assertFailsWith<ConfigurationValueException> { numbers.getULongOrNull("ulongOver") }
        assertEquals(listOf(0uL, ULong.MAX_VALUE), numbers.getULongList("ulongs"))
    }

    @Test
    fun `float getters reject a finite number that overflows a float`() {
        val numbers = yaml("huge: 1.0e39\ninfinite: .inf\nsmall: 1.5")
        assertFailsWith<ConfigurationValueException> { numbers.getFloatOrThrow("huge") }
        assertEquals(Float.POSITIVE_INFINITY, numbers.getFloatOrThrow("infinite"))
        assertEquals(1.5f, numbers.getFloatOrNull("small"))
        assertFailsWith<ConfigurationValueException> { yaml("floats: [1, 1.0e39]").getFloatListOrThrow("floats") }
    }

    @Test
    fun `string getters reject sections and lists instead of printing them`() {
        val strings = yaml("text: hello\nnumber: 5\nflag: true\nlist: [a, b]\nsection:\n  a: 1")
        assertEquals("hello", strings.getStringOrThrow("text"))
        assertEquals("5", strings.getStringOrNull("number"))
        assertEquals("true", strings.getStringOrThrow("flag"))
        assertNull(strings.getStringOrNull("missing"))
        assertEquals("Value at \"list\" is not a string: \"[a, b]\"", assertFailsWith<ConfigurationValueException> { strings.getStringOrThrow("list") }.message)
        assertEquals("Value at \"section\" is a section, expected a string", assertFailsWith<ConfigurationValueException> { strings.getStringOrNull("section") }.message)
        assertFailsWith<ConfigurationValueException> { strings.getStringOrThrow("") }
        assertFailsWith<ConfigurationValueException> { strings.ifStringPresent("list") {} }
        assertFailsWith<ConfigurationValueException> { yaml("map:\n  a: [1]").getConfigurationSectionOrThrow("map").asStringsMap() }
    }

    @Test
    fun `paths use the configuration's own separator`() {
        val custom = YamlConfiguration().apply {
            options().pathSeparator('/')
            loadFromString("outer:\n  inner:\n    value: text")
        }
        val exception = assertFailsWith<ConfigurationValueException> { custom.getIntOrThrow("outer/inner/value") }
        assertEquals("Value at \"outer/inner/value\" is not an int: \"text\"", exception.message)
        assertEquals("outer/inner/missing", custom.getConfigurationSectionOrThrow("outer/inner").pathOf("missing"))
    }

    @Test
    fun `enum messages list at most twenty constants`() {
        val exception = assertFailsWith<ConfigurationValueException> { yaml("letter: none").getEnumElementOrThrow(key = "letter", enumClass = Letter::class.java) }
        assertEquals(
            "Value at \"letter\" is not one of ${Letter.entries.take(20).joinToString()}, ... (26 in total): \"none\"",
            exception.message,
        )
    }

    @Test
    fun `Bukkit types are read back and wrong shapes are reported`() {
        val written = YamlConfiguration().apply {
            set("color", Color.fromRGB(1, 2, 3))
            set("vector", Vector(1.0, 2.0, 3.0))
        }
        val read = yaml(written.saveToString() + "\ntext: hello")
        assertEquals(Color.fromRGB(1, 2, 3), read.getColorOrThrow("color"))
        assertEquals(Vector(1.0, 2.0, 3.0), read.getVectorOrThrow("vector"))
        assertEquals("Value at \"text\" is not a Color: \"hello\"", assertFailsWith<ConfigurationValueException> { read.getColorOrThrow("text") }.message)
        assertEquals("Value at \"text\" is not a Vector: \"hello\"", assertFailsWith<ConfigurationValueException> { read.getVectorOrThrow("text") }.message)
        assertEquals("Value at \"text\" is not a Location: \"hello\"", assertFailsWith<ConfigurationValueException> { read.getLocationOrThrow("text") }.message)
        assertEquals("Value at \"text\" is not an ItemStack: \"hello\"", assertFailsWith<ConfigurationValueException> { read.getItemStackOrThrow("text") }.message)
        assertEquals("No value present at \"missing\"", assertFailsWith<ConfigurationValueException> { read.getOfflinePlayerOrThrow("missing") }.message)
    }
}

private enum class Letter { A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z }
