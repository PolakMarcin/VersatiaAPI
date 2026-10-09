package com.github.marcoral.versatia.util.type

import com.github.marcoral.versatia.util.validation.validateIn
import com.github.marcoral.versatia.util.validation.validateNotZero

/** A percentage in the closed range 0..100. */
@JvmInline
value class Percent private constructor(val value: Double) : Comparable<Percent> {
    init {
        require(value in 0.0..100.0) { "Percent must be between 0 and 100, got $value" }
    }

    /** The same quantity in the range 0..1. */
    val normalizedValue get() = value / 100

    override fun compareTo(other: Percent) = value.compareTo(other.value)

    override fun toString() = "$value%"

    companion object {
        val ZERO = Percent(0.0)
        val FULL = Percent(100.0)

        fun of(value: Double) = Percent(value)

        fun ofNormalizedValue(value: Double) =
            Percent(value.validateIn(0.0..1.0) { "Normalized value of percent must be between 0 and 1, got $value" } * 100)

        /** Share of [current] in [total]; throws when the share is outside 0..100%. */
        fun of(current: Number, total: Number) = Percent(ratio(current, total) * 100)

        /** Share of [current] in [total], clamped to 0..100%. */
        fun ofCoerced(current: Number, total: Number) = Percent(ratio(current, total).coerceIn(0.0, 1.0) * 100)

        /** Parses strings such as `"12.5%"`; throws on anything else. */
        fun parse(percentString: String) = Percent(
            numberPart(percentString)
                ?: throw IllegalArgumentException("Not a percent string (expected a number followed by '%'): \"$percentString\""),
        )

        /** Parses strings such as `"12.5%"`; returns null for malformed or out-of-range input. */
        fun parseOrNull(percentString: String) =
            numberPart(percentString)?.takeIf { it in 0.0..100.0 }?.let(::Percent)

        private fun ratio(current: Number, total: Number) =
            current.toDouble() / total.toDouble().validateNotZero { "Total must not be zero" }

        private fun numberPart(percentString: String) = percentString.trim()
            .takeIf { it.endsWith('%') }
            ?.dropLast(1)
            ?.trim()
            ?.toDoubleOrNull()
    }
}
