package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.util.config.getConfigurationSectionOrThrow
import org.bukkit.configuration.ConfigurationSection

/**
 * Reads a `@Value` property of a type the runtime does not read by itself: a data class from a
 * section, a list of definitions from the children of one, ticks from seconds. The mapper is
 * named in `@Value(mapper = ...)`, is an `object` or a class with a no-argument constructor, and
 * receives the section holding the key and the key, so it reads with the strict getters of
 * `util.config` and reports with their messages. It is called only when the key is present; a
 * missing key follows the nullability of the property, like any value. The compile-time step
 * checks that [T] fits the property. See docs/enhancements.md.
 */
fun interface ValueMapper<out T> {
    fun map(section: ConfigurationSection, key: String): T
}

/**
 * A [ValueMapper] for a value written as a section of its own, the usual shape of a data class:
 * `object Mapper : SectionMapper<PriceRange>() { override fun map(section: ConfigurationSection) = PriceRange(...) }`.
 * [map] receives that section; a key holding anything else is reported as not a section.
 */
abstract class SectionMapper<out T> : ValueMapper<T> {
    abstract fun map(section: ConfigurationSection): T

    final override fun map(section: ConfigurationSection, key: String) = map(section.getConfigurationSectionOrThrow(key))
}

/** Stands for "no mapper" in `@Value`, because annotations cannot hold `null`. */
object NoMapper : ValueMapper<Nothing> {
    override fun map(section: ConfigurationSection, key: String) = throw UnsupportedOperationException("NoMapper maps nothing")
}
