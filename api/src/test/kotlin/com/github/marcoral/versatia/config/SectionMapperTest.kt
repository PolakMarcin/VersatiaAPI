package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.util.config.ConfigurationValueException
import com.github.marcoral.versatia.util.config.getIntOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

private data class PriceRange(val min: Int, val max: Int)

private object PriceRangeMapper : SectionMapper<PriceRange>() {
    override fun map(section: ConfigurationSection) = PriceRange(section.getIntOrThrow("min"), section.getIntOrThrow("max"))
}

class SectionMapperTest {
    private val config = YamlConfiguration().apply {
        loadFromString(
            """
            shop:
              range:
                min: 1
                max: 5
              flat: 3
            """.trimIndent(),
        )
    }
    private val shop = config.getConfigurationSection("shop")!!

    @Test
    fun `maps the section the key holds`() = assertEquals(PriceRange(1, 5), PriceRangeMapper.map(shop, "range"))

    @Test
    fun `reports a key that holds no section with its full path`() = assertEquals(
        "Value at \"shop.flat\" is not a section: \"3\"",
        assertFailsWith<ConfigurationValueException> { PriceRangeMapper.map(shop, "flat") }.message,
    )
}
