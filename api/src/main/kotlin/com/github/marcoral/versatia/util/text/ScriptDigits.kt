package com.github.marcoral.versatia.util.text

private const val SUBSCRIPT_DIGITS = "₀₁₂₃₄₅₆₇₈₉"
private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
private const val SUBSCRIPT_MINUS = '₋'
private const val SUPERSCRIPT_MINUS = '⁻'

/** Converts a string of digits with an optional leading minus sign to its subscript form. */
fun String.toSubscript() = mapDigits(SUBSCRIPT_DIGITS, SUBSCRIPT_MINUS)

/** Converts a string of digits with an optional leading minus sign to its superscript form. */
fun String.toSuperscript() = mapDigits(SUPERSCRIPT_DIGITS, SUPERSCRIPT_MINUS)

fun Int.toSubscript() = toString().toSubscript()
fun Long.toSubscript() = toString().toSubscript()
fun UInt.toSubscript() = toString().toSubscript()
fun ULong.toSubscript() = toString().toSubscript()

fun Int.toSuperscript() = toString().toSuperscript()
fun Long.toSuperscript() = toString().toSuperscript()
fun UInt.toSuperscript() = toString().toSuperscript()
fun ULong.toSuperscript() = toString().toSuperscript()

private fun String.mapDigits(digits: String, minus: Char) = buildString(length) {
    this@mapDigits.forEachIndexed { index, char ->
        append(
            when {
                char.isDigit() -> digits[char.digitToInt()]
                char == '-' && index == 0 -> minus
                else -> throw IllegalArgumentException("Not an integer string: \"${this@mapDigits}\"")
            },
        )
    }
}
