package com.github.marcoral.versatia.util.config

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.plugin.Plugin

class ConfigurationFilesTest {
    private val dataFolder = Files.createTempDirectory("versatia-files").toFile()

    private val plugin = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
        if (method.name == "getDataFolder") dataFolder else throw UnsupportedOperationException(method.name)
    } as Plugin

    @AfterTest
    fun cleanUp() {
        dataFolder.deleteRecursively()
    }

    private fun write(path: String, text: String) = dataFolder.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun `yaml files are found recursively in the same order on every platform`() {
        write("lang/aZ.yml", "a: 1")
        write("lang/a/x.yaml", "a: 2")
        write("lang/b.YML", "a: 3")
        write("lang/notes.txt", "ignored")
        val names = dataFolder.resolve("lang").yamlFiles().map { it.relativeTo(dataFolder.resolve("lang")).invariantSeparatorsPath }
        assertEquals(listOf("a/x.yaml", "aZ.yml", "b.YML"), names)
        assertEquals(emptyList(), dataFolder.resolve("absent").yamlFiles())
    }

    @Test
    fun `plugin helpers load every file and propagate a broken one`() {
        write("lang/a.yml", "value: 1")
        write("lang/sub/b.yml", "value: 2")
        val values = plugin.mapYamlFiles("lang") { _, config -> config.getIntOrThrow("value") }
        assertEquals(listOf(1, 2), values.values.toList())
        write("lang/sub/c.yml", "value: [unclosed")
        assertFailsWith<InvalidConfigurationException> { plugin.forEachYamlFile("lang") { _, _ -> } }
    }

    @Test
    fun `a file that is not UTF-8 is reported instead of decoded with replacement characters`() {
        val file = File(dataFolder, "pl.yml").apply { writeBytes("bought: \"Kupiłeś\"".toByteArray(charset("windows-1250"))) }
        assertContains(assertFailsWith<InvalidConfigurationException> { loadYamlOrThrow(file) }.message!!, "pl.yml is not UTF-8 encoded")
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bought: \"Kupiłeś\"".toByteArray())
        assertEquals("Kupiłeś", loadYamlOrThrow(file).getStringOrThrow("bought"))
    }
}
