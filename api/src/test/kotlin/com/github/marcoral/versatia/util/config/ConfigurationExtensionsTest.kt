package com.github.marcoral.versatia.util.config

import com.github.marcoral.versatia.util.type.Percent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.configuration.file.YamlConfiguration

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
        assertEquals("Element 1 of list at \"badAmounts\" is not a non-negative int: \"-2\"", exception.message)
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
}
