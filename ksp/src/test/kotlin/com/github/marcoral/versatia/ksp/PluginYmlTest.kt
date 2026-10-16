package com.github.marcoral.versatia.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginYmlTest {
    @Test
    fun `reads the main class`() {
        val yml = """
            name: Alpha
            version: '1.0'
            main: com.example.alpha.AlphaPlugin
            api-version: '1.21'
        """.trimIndent()
        assertEquals("com.example.alpha.AlphaPlugin", PluginYml.mainClassOf(yml))
    }

    @Test
    fun `accepts quoted values`() {
        assertEquals("com.example.Main", PluginYml.mainClassOf("main: \"com.example.Main\""))
        assertEquals("com.example.Main", PluginYml.mainClassOf("main: 'com.example.Main'"))
    }

    @Test
    fun `rejects mismatched quotes`() {
        assertNull(PluginYml.mainClassOf("main: \"com.example.Main'"))
        assertNull(PluginYml.mainClassOf("main: com.example.Main\""))
    }

    @Test
    fun `allows a trailing comment, separated by whitespace as YAML requires`() {
        assertEquals("com.example.Main", PluginYml.mainClassOf("main: com.example.Main # the entry point"))
        assertEquals("com.example.Main", PluginYml.mainClassOf("main: 'com.example.Main'   # quoted"))
        assertNull(PluginYml.mainClassOf("main: com.example.Main#not-a-comment"))
    }

    @Test
    fun `reads only the top-level key`() {
        val nested = """
            # Alpha's descriptor
            name: Alpha
            commands:
              main:
                description: the main command
              other:
                main: com.example.NotTheMain
        """.trimIndent()
        assertNull(PluginYml.mainClassOf(nested))

        val indented = "  name: Alpha\r\n  main: com.example.Main\r\n  depend:\r\n    - VersatiaCore\r\n"
        assertEquals("com.example.Main", PluginYml.mainClassOf(indented), "a uniformly indented root is still the root")
        assertNull(PluginYml.mainClassOf("name: Alpha\n  main: com.example.Main"), "an indented key is not a top-level key")
    }

    @Test
    fun `returns null without a main entry`() {
        assertNull(PluginYml.mainClassOf("name: Alpha\ndomain: com.example"))
        assertNull(PluginYml.mainClassOf(""))
    }
}
