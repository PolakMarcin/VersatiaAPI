package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.VersatiaInternalApi
import com.github.marcoral.versatia.util.reflect.forceGetValue
import com.github.marcoral.versatia.util.reflect.forceSetValue
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties

sealed interface Setter {
    fun set(classLoader: ClassLoader, instance: Any, value: Any?)

    /** Generated code stores the value itself; used whenever the property is visible to that code. */
    @VersatiaInternalApi
    class Direct(private val assign: (Any, Any?) -> Unit) : Setter {
        override fun set(classLoader: ClassLoader, instance: Any, value: Any?) = assign(instance, value)

        override fun toString() = "Direct"
    }

    /**
     * Fallback for private properties, setters or classes. Names are JVM binary names. With [via],
     * the property is read and the value stored in the named mutable property of what it holds:
     * `holder.value = ...` for a read-only property holding a cell.
     */
    @VersatiaInternalApi
    data class Reflective(val binaryClassName: String, val propertyName: String, val via: String? = null) : Setter {
        override fun set(classLoader: ClassLoader, instance: Any, value: Any?) {
            val clazz = loadDescribedClass(binaryClassName, false, classLoader)
            if (via == null) return mutable(clazz.kotlin.declaredMemberProperties, propertyName, binaryClassName).forceSetValue(instance, value)
            @Suppress("UNCHECKED_CAST")
            val property = clazz.kotlin.declaredMemberProperties.firstOrNull { it.name == propertyName } as? KProperty1<Any, Any?>
                ?: throw EnhancementException("$binaryClassName has no property $propertyName")
            val holder = property.forceGetValue(instance) ?: throw EnhancementException("$binaryClassName.$propertyName holds nothing to store $via in")
            mutable(holder.javaClass.kotlin.memberProperties, via, holder.javaClass.name).forceSetValue(holder, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun mutable(properties: Collection<KProperty1<out Any, *>>, name: String, owner: String) =
            properties.firstOrNull { it.name == name } as? KMutableProperty1<Any, Any?>
                ?: throw EnhancementException("$owner has no mutable property $name")
    }
}
