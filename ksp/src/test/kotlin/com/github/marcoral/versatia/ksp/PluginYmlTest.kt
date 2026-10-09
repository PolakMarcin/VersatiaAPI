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
    fun `returns null without a main entry`() = assertNull(PluginYml.mainClassOf("name: Alpha\ndomain: com.example"))
}
