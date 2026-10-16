package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.VersatiaInternalApi

/**
 * Generated at compile time for every Versatia plugin. Lives next to the plugin's main class under
 * the name [CLASS_SIMPLE_NAME]; see docs/enhancements.md.
 *
 * The format version is split into plain `Int`s so that a runtime can read it from a descriptor of
 * any format before deciding whether it understands the rest.
 */
@VersatiaInternalApi
interface VersatiaPluginDescriptor {
    val formatMajor: Int
    val formatMinor: Int
    val elements: List<EnhancedElement>

    val formatVersion get() = DescriptorVersion(formatMajor, formatMinor)

    companion object {
        /** Format produced by this VersatiaAPI and understood by a VersatiaCore built with it. */
        val FORMAT_VERSION = DescriptorVersion(1, 3)

        const val CLASS_SIMPLE_NAME = "VersatiaDescriptor"

        fun classNameFor(pluginMainClass: String) =
            pluginMainClass.substringBeforeLast('.', "").let { if (it.isEmpty()) CLASS_SIMPLE_NAME else "$it.$CLASS_SIMPLE_NAME" }
    }
}
