package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.VersatiaInternalApi

/**
 * Version of the descriptor format, independent of the library version. A major change breaks
 * compatibility, a minor change adds element kinds or fields that older runtimes do not know.
 * See docs/enhancements.md.
 */
@VersatiaInternalApi
data class DescriptorVersion(val major: Int, val minor: Int) : Comparable<DescriptorVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Version components must not be negative: $this" }
    }

    override fun compareTo(other: DescriptorVersion) =
        compareValuesBy(this, other, DescriptorVersion::major, DescriptorVersion::minor)

    override fun toString() = "$major.$minor"

    companion object {
        fun parse(text: String): DescriptorVersion {
            val parts = text.trim().split('.')
            require(parts.size == 2) { "Expected a version like 1.2, got \"$text\"" }
            val (major, minor) = parts.map {
                it.toIntOrNull() ?: throw IllegalArgumentException("Expected a version like 1.2, got \"$text\"")
            }
            return DescriptorVersion(major, minor)
        }
    }
}
