package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.MemberTarget
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.Setter
import com.github.marcoral.versatia.engine.descriptor.TypeReference
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.bukkit.plugin.Plugin

class EnhancementProcessorExtensionsTest {
    object Clock

    class Shop

    private val plugin = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
        if (method.name == "getName") "Sample" else throw UnsupportedOperationException(method.name)
    } as Plugin

    private val processor = object : EnhancementProcessor {
        override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Unit
    }

    private fun context(beans: BeanResolver? = null) = object : EnhancementContext {
        override val plugin = this@EnhancementProcessorExtensionsTest.plugin
        override val event = LifecycleEvent.START
        override val state = object : StateStore {
            override fun <T : Any> getOrPut(key: StateKey<T>, create: () -> T) = get(key) ?: create()

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(key: StateKey<T>) = if (key === BeanResolver.KEY) beans as T? else null
        }
        override val runtimeState = state
        override fun onPluginEnhanced(action: () -> Unit) = action()
    }

    private fun beans(vararg instances: Pair<String, Any>) = object : BeanResolver {
        override fun resolve(requester: String, parameters: List<Parameter>) = parameters.map { "$requester:${it.type}" }
        override fun supply(className: String, index: Int, value: () -> Any?) = Unit
        override fun onInstanceCreated(className: String, action: (instance: Any) -> Unit) = false
        override fun instanceOf(className: String) = instances.toMap()[className]
        override fun instancesOf(className: String) = listOfNotNull(instanceOf(className))
    }

    private fun function(type: Class<*>, owner: MemberOwner, parameters: List<Parameter> = emptyList()) =
        FunctionTarget(type.kotlin.qualifiedName!!, type.name, "tick", owner, emptyList(), parameters, Invoker.Direct { _, _ -> })

    private fun property(type: Class<*>, owner: MemberOwner) =
        PropertyTarget(type.kotlin.qualifiedName!!, type.name, "rate", owner, TypeReference("kotlin.Int"), emptyList(), Setter.Direct { _, _ -> })

    private val shopName = Shop::class.qualifiedName!!

    @Test
    fun `targetAs returns the target of the expected kind`() {
        val target = function(Shop::class.java, MemberOwner.INSTANCE)
        val element = EnhancedElement("Timed", Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence("Timed"), target)
        assertSame(target, element.targetAs<FunctionTarget>(processor))
        assertSame(target, element.targetAs<MemberTarget>(processor))
    }

    @Test
    fun `targetAs names the processor, the accepted kind and the element it got`() {
        val element = EnhancedElement("Timed", Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence("Timed"), property(Shop::class.java, MemberOwner.INSTANCE))
        val error = assertFailsWith<EnhancementException> { element.targetAs<FunctionTarget>(processor) }
        assertEquals("${processor.javaClass.name} can only enhance functions, got the property $shopName.rate", error.message)
    }

    @Test
    fun `failure prefixes the plugin name and keeps the cause`() {
        val cause = IllegalStateException()
        val error = context().failure("it broke", cause)
        assertEquals("Plugin Sample: it broke", error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `receiverOf finds the plugin, an object and a bean`() {
        val shop = Shop()
        val context = context(beans(shopName to shop))
        assertSame(plugin, context.receiverOf(function(Shop::class.java, MemberOwner.PLUGIN)))
        assertSame(Clock, context.receiverOf(function(Clock::class.java, MemberOwner.OBJECT)))
        assertSame(shop, context.receiverOf(property(Shop::class.java, MemberOwner.INSTANCE)))
    }

    @Test
    fun `receiverOf says what it could not find`() {
        val notObject = assertFailsWith<EnhancementException> { context().receiverOf(function(Shop::class.java, MemberOwner.OBJECT)) }
        assertEquals("Plugin Sample: $shopName is not a Kotlin object, so there is no instance to call $shopName.tick() on", notObject.message)
        val noBean = assertFailsWith<EnhancementException> { context(beans()).receiverOf(property(Shop::class.java, MemberOwner.INSTANCE)) }
        assertEquals("Plugin Sample: no bean $shopName exists to set $shopName.rate on", noBean.message)
        val noBeans = assertFailsWith<EnhancementException> { context().receiverOf(function(Shop::class.java, MemberOwner.INSTANCE)) }
        assertEquals("Plugin Sample: no bean $shopName exists to call $shopName.tick() on", noBeans.message)
    }

    @Test
    fun `inject resolves through the plugin's beans and needs none for no parameters`() {
        assertEquals(emptyList(), context().inject("tick()", emptyList()))
        assertEquals(listOf("tick():kotlin.Int"), context(beans()).inject("tick()", listOf(Parameter("kotlin.Int"))))
        val error = assertFailsWith<EnhancementException> { context().inject("tick()", listOf(Parameter("kotlin.Int"))) }
        assertEquals("Plugin Sample: tick() needs injected parameters, but the plugin has no beans", error.message)
    }
}
