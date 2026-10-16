package com.github.marcoral.versatia

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.bukkit.plugin.Plugin

class PluginRegistryTest {
    private fun plugin(name: String) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
        if (method.name == "getName") name else throw UnsupportedOperationException(method.name)
    } as Plugin

    private fun registry(vararg entries: Pair<String, Plugin>) = object : PluginRegistry {
        override val plugins = linkedMapOf(*entries)
    }

    @Test
    fun `getOrThrow returns the plugin registered under the name`() {
        val shop = plugin("Shop")
        assertSame(shop, registry("shop" to shop, "quests" to plugin("Quests")).getOrThrow("shop"))
    }

    @Test
    fun `getOrThrow lists the running names when the name is unknown`() {
        val unknown = assertFailsWith<NoSuchElementException> { registry("shop" to plugin("Shop"), "quests" to plugin("Quests")).getOrThrow("shpo") }
        assertEquals("No Versatia plugin is called \"shpo\". Running: shop, quests.", unknown.message)
        assertEquals("No Versatia plugin is called \"shop\". Running: none.", assertFailsWith<NoSuchElementException> { registry().getOrThrow("shop") }.message)
    }
}
