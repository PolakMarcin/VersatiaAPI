package com.github.marcoral.versatia.ksp.model

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.Setter
import com.github.marcoral.versatia.engine.descriptor.TypeReference
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.ksp.di.PluginDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.joinToCode

/**
 * Turns the compile-time model into the `VersatiaDescriptor` object; see docs/enhancements.md.
 * The elements of a large plugin are built in private functions of at most [CHUNK] elements
 * each, so that no method of the descriptor exceeds the JVM's 64 KB limit.
 */
internal object DescriptorGenerator {
    private const val CHUNK = 50
    private val descriptorInterface = VersatiaPluginDescriptor::class.asClassName()
    private val enhancedElement = EnhancedElement::class.asClassName()
    private val annotationOccurrence = AnnotationOccurrence::class.asClassName()
    private val parameter = Parameter::class.asClassName()
    private val classTarget = ClassTarget::class.asClassName()
    private val functionTarget = FunctionTarget::class.asClassName()
    private val propertyTarget = PropertyTarget::class.asClassName()
    private val pluginTarget = PluginTarget::class.asClassName()
    private val memberOwner = MemberOwner::class.asClassName()
    private val typeReference = TypeReference::class.asClassName()
    private val direct = Instantiator.Direct::class.asClassName()
    private val reflective = Instantiator.Reflective::class.asClassName()
    private val objectInstance = Instantiator.ObjectInstance::class.asClassName()
    private val withDefaults = Instantiator.WithDefaults::class.asClassName()
    private val directInvoker = Invoker.Direct::class.asClassName()
    private val reflectiveInvoker = Invoker.Reflective::class.asClassName()
    private val directSetter = Setter.Direct::class.asClassName()
    private val reflectiveSetter = Setter.Reflective::class.asClassName()
    private val eventType = EventType::class.asClassName()
    private val suppress = Suppress::class.asClassName()
    private val elementList = LIST.parameterizedBy(enhancedElement)
    private val pluginTargetCall = CodeBlock.of("pluginTarget()")
    private val fixedNames = listOf(
        descriptorInterface, enhancedElement, annotationOccurrence, parameter, classTarget, functionTarget, propertyTarget, pluginTarget, memberOwner,
        typeReference, direct, reflective, objectInstance, withDefaults, directInvoker, reflectiveInvoker, directSetter, reflectiveSetter, eventType, suppress, LIST, INT,
    )

    fun generate(model: DescriptorModel): FileSpec {
        val elements = elements(model)
        val chunks = elements.chunked(CHUNK)
        // A getter, not an initializer: the runtime must be able to read the format version
        // before any class the elements reference is resolved
        val getter = FunSpec.getterBuilder()
        if (chunks.size <= 1) getter.addStatement("return %L", listLiteral(elements))
        else getter.addStatement("return %L", chunks.indices.map { CodeBlock.of("elements%L()", it + 1) }.joinToCode(" + "))
        val descriptor = TypeSpec.objectBuilder(VersatiaPluginDescriptor.CLASS_SIMPLE_NAME)
            .addSuperinterface(descriptorInterface)
            .addProperty(versionProperty("formatMajor", VersatiaPluginDescriptor.FORMAT_VERSION.major))
            .addProperty(versionProperty("formatMinor", VersatiaPluginDescriptor.FORMAT_VERSION.minor))
            .addProperty(PropertySpec.builder("elements", elementList, KModifier.OVERRIDE).getter(getter.build()).build())
        if (chunks.size > 1) chunks.forEachIndexed { index, chunk ->
            descriptor.addFunction(FunSpec.builder("elements${index + 1}").addModifiers(KModifier.PRIVATE).returns(elementList).addStatement("return %L", listLiteral(chunk)).build())
        }
        if (model.plugin != null && usesPluginTarget(model)) descriptor.addFunction(
            FunSpec.builder("pluginTarget").addModifiers(KModifier.PRIVATE).returns(pluginTarget).addStatement("return %L", pluginTargetOf(model.plugin)).build(),
        )
        // A direct setter or constructor call casts a value to, say, List<Int>, which the compiler cannot check
        val unchecked = model.properties.any { it.reachable && it.stored.arguments.isNotEmpty() } ||
            classes(model).any { clazz -> clazz.isDirect && clazz.parameters.any { it.typeName is ParameterizedTypeName } }
        if (unchecked) descriptor.addAnnotation(AnnotationSpec.builder(suppress).addMember("%S", "UNCHECKED_CAST").build())

        val file = FileSpec.builder(model.packageName, VersatiaPluginDescriptor.CLASS_SIMPLE_NAME).addFileComment("Generated by VersatiaAPI. Do not edit.")
        aliases(model).forEach { (name, alias) -> file.addAliasedImport(name, alias) }
        return file.addType(descriptor.build()).build()
    }

    /**
     * Aliases for the imported types whose simple name a class of the plugin's own package also
     * has. KotlinPoet imports every type of another package, `kotlin.collections.Map` included,
     * and writes the package's own classes unqualified, which the import would then hide.
     */
    private fun aliases(model: DescriptorModel): Map<ClassName, String> {
        if (model.shadowedNames.isEmpty()) return emptyMap()
        val named = fixedNames.toMutableSet()
        for (property in model.properties.filter { it.reachable }) named += listOf(property.owner) + property.stored.asTypeName().classNames()
        for (function in model.functions.filter { it.reachable }) named += listOf(function.owner) + function.parameters.map { it.type }
        for (clazz in classes(model).filter { it.reachable }) named += listOf(clazz.type) + clazz.parameters.flatMap { it.typeName.classNames() }
        val taken = model.shadowedNames.toMutableSet()
        return named.filter { it.packageName != model.packageName && it.simpleNames.first() in model.shadowedNames }
            .sortedBy { it.canonicalName }
            .associateWith { name ->
                var alias = name.packageName.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() } + name.simpleNames.joinToString("")
                while (!taken.add(alias)) alias += "_"
                alias
            }
    }

    private fun TypeName.classNames(): List<ClassName> = when (this) {
        is ClassName -> listOf(ClassName(packageName, simpleNames))
        is ParameterizedTypeName -> listOf(rawType) + typeArguments.flatMap { it.classNames() }
        else -> emptyList()
    }

    private fun classes(model: DescriptorModel) = model.beans.map { it.asClass() } + model.subclasses.mapNotNull { it.clazz }

    /** Constructors with optional parameters are called through reflection, which can leave them out. */
    private val ClassDeclaration.hasOptionalParameters get() = parameters.any { it.optional }

    private val ClassDeclaration.isDirect get() = reachable && !isObject && !hasOptionalParameters

    private fun usesPluginTarget(model: DescriptorModel) =
        model.plugin?.enhancements?.any { it.enhancements.isNotEmpty() } == true || model.subclasses.any { it.clazz == null }

    private fun versionProperty(name: String, value: Int) = PropertySpec.builder(name, INT, KModifier.OVERRIDE).initializer("%L", value).build()

    private fun listLiteral(elements: List<CodeBlock>) =
        if (elements.isEmpty()) CodeBlock.of("emptyList()")
        else CodeBlock.builder().add("listOf(\n⇥").apply { elements.forEach { add("%L,\n", it) } }.add("⇤)").build()

    private fun elements(model: DescriptorModel) = listOf(
        model.plugin?.let(::pluginElements).orEmpty(),
        model.beans.flatMap { bean -> bean.enhancements.map { element(it, bean.scope, classTarget(bean.asClass()), bean.type) } },
        model.subclasses.map { subclass -> element(subclass.enhancement, subclass.annotation, subclass.clazz?.let(::classTarget) ?: pluginTargetCall, subclass.origin) },
        // Properties before functions: their processors complete in this order, so values are set before functions run
        model.properties.flatMap { property -> property.enhancements.map { element(it, property.annotation, propertyTarget(property), property.owner) } },
        model.functions.flatMap { function -> function.enhancements.map { element(it, function.annotation, functionTarget(function), function.owner) } },
    ).flatten()

    private fun element(enhancement: Enhancement, annotation: AnnotationRef, target: CodeBlock, origin: ClassName) = CodeBlock.builder()
        .add("%T(\n⇥", enhancedElement)
        .add("processorClass = %S,\n", enhancement.processorClass)
        .add("phase = %T.%L,\n", eventType, enhancement.phase.name)
        .add("annotation = %L,\n", occurrence(annotation))
        .add("target = %L,\n", target)
        .add("origin = %S,\n", origin.canonicalName)
        .add("⇤)")
        .build()

    /** One element per enhancement of the main class, all sharing the target `pluginTarget()` builds. */
    private fun pluginElements(plugin: PluginDeclaration) = plugin.enhancements.flatMap { enhanced ->
        enhanced.enhancements.map { element(it, enhanced.annotation, pluginTargetCall, enhanced.origin) }
    }

    private fun pluginTargetOf(plugin: PluginDeclaration) = CodeBlock.builder()
        .add("%T(\n⇥", pluginTarget)
        .add("className = %S,\n", plugin.type.canonicalName)
        .add("assignableTypes = %L,\n", stringList(plugin.assignableTypes.map { it.canonicalName }))
        .add("annotations = %L,\n", occurrences(plugin.annotations))
        .add("⇤)")
        .build()

    private fun classTarget(clazz: ClassDeclaration) = CodeBlock.builder()
        .add("%T(\n⇥", classTarget)
        .add("className = %S,\n", clazz.type.canonicalName)
        .add("assignableTypes = %L,\n", stringList(clazz.assignableTypes.map { it.canonicalName }))
        .add("annotations = %L,\n", occurrences(clazz.annotations))
        .add("constructorParameters = %L,\n", parameters(clazz.parameters))
        .add("instantiator = %L,\n", instantiator(clazz))
        .add("⇤)")
        .build()

    private fun instantiator(clazz: ClassDeclaration) = when {
        clazz.isObject && clazz.reachable -> CodeBlock.of("%T { _ -> %T }", direct, clazz.type)
        clazz.isObject -> CodeBlock.of("%T(%S)", objectInstance, clazz.type.reflectionName())
        clazz.hasOptionalParameters -> CodeBlock.of("%T(%S, %L)", withDefaults, clazz.type.reflectionName(), stringList(clazz.parameters.map { it.jvmType }))
        clazz.reachable -> CodeBlock.of("%T { %L -> %T(%L) }", direct, if (clazz.parameters.isEmpty()) "_" else "args", clazz.type, castArguments(clazz.parameters))
        else -> CodeBlock.of("%T(%S, %L)", reflective, clazz.type.reflectionName(), stringList(clazz.parameters.map { it.jvmType }))
    }

    private fun functionTarget(function: FunctionDeclaration) = CodeBlock.builder()
        .add("%T(\n⇥", functionTarget)
        .add("className = %S,\n", function.owner.canonicalName)
        .add("binaryClassName = %S,\n", function.owner.reflectionName())
        .add("functionName = %S,\n", function.name)
        .add("owner = %T.%L,\n", memberOwner, function.ownerKind.name)
        .add("annotations = %L,\n", occurrences(function.annotations))
        .add("parameters = %L,\n", parameters(function.parameters))
        .add("classAnnotations = %L,\n", occurrences(function.classAnnotations))
        .add("invoker = %L,\n", invoker(function))
        .add("⇤)")
        .build()

    private fun invoker(function: FunctionDeclaration) =
        if (function.reachable) directInvoker(function) else reflectiveInvoker(function)

    private fun directInvoker(function: FunctionDeclaration): CodeBlock {
        val onObject = function.ownerKind == MemberOwner.OBJECT
        val instanceParameter = if (onObject) "_" else "instance"
        val argsParameter = if (function.parameters.isEmpty()) "_" else "args"
        val receiver = if (onObject) CodeBlock.of("%T", function.owner) else CodeBlock.of("(instance as %T)", function.owner)
        return CodeBlock.of("%T { %L, %L -> %L.%N(%L) }", directInvoker, instanceParameter, argsParameter, receiver, function.name, castArguments(function.parameters))
    }

    private fun reflectiveInvoker(function: FunctionDeclaration) = CodeBlock.of(
        "%T(%S, %S, %L)",
        reflectiveInvoker,
        function.owner.reflectionName(),
        function.jvmName,
        stringList(function.parameters.map { it.jvmType }),
    )

    private fun propertyTarget(property: PropertyDeclaration) = CodeBlock.builder()
        .add("%T(\n⇥", propertyTarget)
        .add("className = %S,\n", property.owner.canonicalName)
        .add("binaryClassName = %S,\n", property.owner.reflectionName())
        .add("propertyName = %S,\n", property.name)
        .add("owner = %T.%L,\n", memberOwner, property.ownerKind.name)
        .add("type = %L,\n", typeReference(property.type))
        .add("annotations = %L,\n", occurrences(property.annotations))
        .add("classAnnotations = %L,\n", occurrences(property.classAnnotations))
        .add("setter = %L,\n", setter(property))
        .apply { property.constructorParameter?.let { add("constructorParameter = %L,\n", it) } }
        .add("⇤)")
        .build()

    private fun typeReference(type: TypeDeclaration): CodeBlock =
        if (type.arguments.isEmpty() && !type.nullable) CodeBlock.of("%T(%S)", typeReference, type.type.canonicalName)
        else CodeBlock.of("%T(%S, %L, %L)", typeReference, type.type.canonicalName, type.arguments.map(::typeReference).asList(), type.nullable)

    private fun setter(property: PropertyDeclaration): CodeBlock {
        val via = property.via
        val owner = property.owner
        return when {
            !property.reachable && via == null -> CodeBlock.of("%T(%S, %S)", reflectiveSetter, owner.reflectionName(), property.name)
            !property.reachable -> CodeBlock.of("%T(%S, %S, %S)", reflectiveSetter, owner.reflectionName(), property.name, via)
            else -> {
                val target = if (via == null) CodeBlock.of("%N", property.name) else CodeBlock.of("%N.%N", property.name, via)
                val stored = property.stored.asTypeName()
                if (property.ownerKind == MemberOwner.OBJECT) CodeBlock.of("%T { _, value -> %T.%L = value as %T }", directSetter, owner, target, stored)
                else CodeBlock.of("%T { instance, value -> (instance as %T).%L = value as %T }", directSetter, owner, target, stored)
            }
        }
    }

    private fun castArguments(parameters: List<ParameterDeclaration>) = parameters.mapIndexed { index, ref -> CodeBlock.of("args[%L] as %T", index, ref.typeName) }.joinToCode(", ")

    private fun parameters(parameters: List<ParameterDeclaration>) = parameters.map { param ->
        val arguments = mutableListOf(CodeBlock.of("%S", param.type.canonicalName))
        if (param.annotations.isNotEmpty()) arguments += occurrences(param.annotations)
        if (param.optional) arguments += CodeBlock.of("optional = true")
        param.suppliedBy?.let { arguments += CodeBlock.of("suppliedBy = %S", it) }
        CodeBlock.of("%T(%L)", parameter, arguments.joinToCode(", "))
    }.asList()

    private fun occurrences(annotations: List<AnnotationRef>) = annotations.map(::occurrence).asList()

    private fun occurrence(annotation: AnnotationRef) =
        if (annotation.arguments.isEmpty()) CodeBlock.of("%T(%S)", annotationOccurrence, annotation.type.canonicalName)
        else CodeBlock.of(
            "%T(%S, mapOf(%L))",
            annotationOccurrence,
            annotation.type.canonicalName,
            annotation.arguments.entries.map { (name, value) -> CodeBlock.of("%S to %L", name, value(value)) }.joinToCode(", "),
        )

    /** A descriptor value as a Kotlin literal of the same type; see `Attributes` for the supported kinds. */
    private fun value(value: Any?): CodeBlock = when (value) {
        null -> CodeBlock.of("null")
        is String -> CodeBlock.of("%S", value)
        is Char -> CodeBlock.of("%L", charLiteral(value))
        is Boolean -> CodeBlock.of("%L", value)
        is Byte -> CodeBlock.of(if (value < 0) "(%L).toByte()" else "%L.toByte()", value)
        is Short -> CodeBlock.of(if (value < 0) "(%L).toShort()" else "%L.toShort()", value)
        is Int -> if (value == Int.MIN_VALUE) CodeBlock.of("Int.MIN_VALUE") else CodeBlock.of("%L", value)
        is Long -> if (value == Long.MIN_VALUE) CodeBlock.of("Long.MIN_VALUE") else CodeBlock.of("%LL", value)
        is Float -> special(value.toDouble(), "Float") ?: CodeBlock.of("%LF", value)
        is Double -> special(value, "Double") ?: CodeBlock.of("%L", value)
        is List<*> -> if (value.isEmpty()) CodeBlock.of("emptyList<Any?>()") else CodeBlock.of("listOf(%L)", value.map(::value).joinToCode(", "))
        else -> throw IllegalArgumentException("Unsupported descriptor value: $value")
    }

    private fun special(value: Double, type: String) = when {
        value.isNaN() -> CodeBlock.of("%L.NaN", type)
        value == Double.POSITIVE_INFINITY -> CodeBlock.of("%L.POSITIVE_INFINITY", type)
        value == Double.NEGATIVE_INFINITY -> CodeBlock.of("%L.NEGATIVE_INFINITY", type)
        else -> null
    }

    /** Printable ASCII as itself, anything else as a Unicode escape, which a raw control character or line break would break. */
    private fun charLiteral(char: Char) = when (char) {
        '\\' -> "'\\\\'"
        '\'' -> "'\\''"
        in ' '..'~' -> "'$char'"
        else -> "'\\u%04x'".format(char.code)
    }

    private fun stringList(values: List<String>) = values.map { CodeBlock.of("%S", it) }.asList()

    /** The items as a `listOf(...)` literal, or `emptyList()`. */
    private fun List<CodeBlock>.asList() =
        if (isEmpty()) CodeBlock.of("emptyList()") else CodeBlock.of("listOf(%L)", joinToCode(", "))
}
