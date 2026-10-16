package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.util.type.Percent
import kotlin.reflect.KClass

/**
 * Sets the annotated property from a YAML file in the plugin's data folder: the value at [key],
 * a non-empty dotted path, of [file], a path relative to the data folder, `config.yml` unless
 * given. The values are read when the plugin starts and again at every reload, before the
 * plugin's `Reloadable` beans are reloaded and before any `@AutoInvoke` function of the event
 * runs, so `/versatia reload` picks changes up without a line of code. The property belongs to
 * the plugin's main class, an `object` or a bean, and is either a `var` or a `val` holding a
 * [PreviousValueTracking], which is set instead and read by the type it wraps. That type is one
 * of those listed in [ValueTypes], or any other with a [mapper] that reads it. A value that is
 * missing is an error unless the type is nullable; a value of the wrong shape is always one, and
 * nothing is set when any value of the plugin fails. Nothing falls back to a file shipped in the
 * JAR. In the primary constructor of a bean the property is a `var`, whose value is read before
 * the bean is created and passed to the constructor, or a `val` of a [PreviousValueTracking],
 * which receives a cell holding the value; both are updated at every reload. See
 * docs/enhancements.md.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@VersatiaEnhancement(ConfigProcessors.VALUE, enhancementPhase = EventType.PLUGIN_RELOAD_OR_START)
annotation class Value(val key: String, val file: String = DEFAULT_FILE, val mapper: KClass<out ValueMapper<*>> = NoMapper::class) {
    companion object {
        /** The file a value is read from when none is named. */
        const val DEFAULT_FILE = "config.yml"
    }
}

/** The VersatiaCore processors of this package, by name: the API does not see the runtime. */
object ConfigProcessors {
    const val VALUE = "com.github.marcoral.versatia.config.ValueEnhancementProcessor"
}

/**
 * The types a `@Value` property may have without a [ValueMapper], by Kotlin qualified name: the
 * compile-time step checks them and the runtime reads them with the strict getters of
 * `util.config`. Any enum is accepted too, matched by constant name ignoring case, and so is
 * `List` or `Set` of a scalar or enum and `Map` from `String` to one; the Bukkit types are read
 * as single values only. A nullable type makes the value optional, and a
 * [PreviousValueTracking] of any of these types is read by the type it wraps.
 */
object ValueTypes {
    /** Read with the strict getters, as single values, as the values of maps and, through the strict list getters, as the elements of lists and sets. */
    val SCALARS = setOf(
        Boolean::class, Int::class, Long::class, Float::class, Double::class, UInt::class, ULong::class, String::class, Percent::class,
    ).map { it.qualifiedName!! }.toSet()

    /** Read with the Bukkit deserialisers, as single values only. Names, not classes: the compile-time step has no Paper API. */
    val BUKKIT = setOf(
        "org.bukkit.Color",
        "org.bukkit.Location",
        "org.bukkit.OfflinePlayer",
        "org.bukkit.util.Vector",
        "org.bukkit.inventory.ItemStack",
        "org.bukkit.configuration.ConfigurationSection",
    )

    const val LIST = "kotlin.collections.List"
    const val SET = "kotlin.collections.Set"
    const val MAP = "kotlin.collections.Map"
}
