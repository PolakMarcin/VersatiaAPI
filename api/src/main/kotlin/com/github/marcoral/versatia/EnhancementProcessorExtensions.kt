package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.ElementTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.MemberTarget
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.util.reflect.objectInstanceOrNull
import kotlin.reflect.KClass

/**
 * The target of this element as [T], such as `element.targetAs<FunctionTarget>(this)` in a
 * processor; an element of another kind fails naming [processor], the kind it accepts and the
 * element it got.
 */
inline fun <reified T : ElementTarget> EnhancedElement.targetAs(processor: EnhancementProcessor) =
    target as? T ?: throw unexpectedTarget(processor, T::class, target)

@PublishedApi
internal fun unexpectedTarget(processor: EnhancementProcessor, expected: KClass<out ElementTarget>, target: ElementTarget): EnhancementException {
    val accepted = when (expected) {
        ClassTarget::class -> "classes"
        PluginTarget::class -> "the main class"
        FunctionTarget::class -> "functions"
        PropertyTarget::class -> "properties"
        MemberTarget::class -> "functions and properties"
        else -> "elements"
    }
    val got = when (target) {
        is ClassTarget -> "the class ${target.className}"
        is PluginTarget -> "the main class ${target.className}"
        is FunctionTarget -> "the function ${target.description}"
        is PropertyTarget -> "the property ${target.description}"
    }
    return EnhancementException("${processor.javaClass.name} can only enhance $accepted, got $got")
}

/** An [EnhancementException] whose message starts with the plugin being enhanced: `Plugin X: message`. */
fun EnhancementContext.failure(message: String, cause: Throwable? = null) = EnhancementException("Plugin ${plugin.name}: $message", cause)

/**
 * The instance [target] is called or set on, by its owner: the plugin being enhanced, the
 * instance of a Kotlin `object`, or the plugin's bean of the declaring class; fails when that
 * class cannot be loaded, is not an `object` or has no bean.
 */
fun EnhancementContext.receiverOf(target: MemberTarget): Any {
    val purpose = when (target) {
        is FunctionTarget -> "call ${target.description} on"
        is PropertyTarget -> "set ${target.description} on"
    }
    return when (target.owner) {
        MemberOwner.PLUGIN -> plugin
        MemberOwner.OBJECT -> try {
            Class.forName(target.binaryClassName, true, classLoader).objectInstanceOrNull()
        } catch (e: ClassNotFoundException) {
            throw failure("${target.className} cannot be loaded to $purpose it", e)
        } ?: throw failure("${target.className} is not a Kotlin object, so there is no instance to $purpose")
        MemberOwner.INSTANCE -> state[BeanResolver.KEY]?.instanceOf(target.className)
            ?: throw failure("no bean ${target.className} exists to $purpose")
    }
}

/**
 * The arguments for [parameters], injected from the plugin's beans with the rules of bean
 * constructor dependencies; [requester] names who asks in errors, such as
 * [MemberTarget.description]. Empty for no parameters; fails when there are some and the plugin
 * has no beans.
 */
fun EnhancementContext.inject(requester: String, parameters: List<Parameter>): List<Any?> =
    if (parameters.isEmpty()) emptyList()
    else state[BeanResolver.KEY]?.resolve(requester, parameters) ?: throw failure("$requester needs injected parameters, but the plugin has no beans")
