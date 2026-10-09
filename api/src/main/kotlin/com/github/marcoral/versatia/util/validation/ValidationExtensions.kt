package com.github.marcoral.versatia.util.validation

inline fun <T> T.validate(predicate: (T) -> Boolean, lazyMessage: () -> Any = { "$this does not match the predicate" }): T {
    require(predicate(this), lazyMessage)
    return this
}

inline fun <T : Comparable<T>> T.validateAtLeast(least: T, lazyMessage: () -> Any = { "Should be at least '$least' but was '$this'" }) =
    validate({ it >= least }, lazyMessage)

inline fun <T : Comparable<T>> T.validateGreaterThan(bound: T, lazyMessage: () -> Any = { "Should be greater than '$bound' but was '$this'" }) =
    validate({ it > bound }, lazyMessage)

inline fun <T : Comparable<T>> T.validateAtMost(most: T, lazyMessage: () -> Any = { "Should be at most '$most' but was '$this'" }) =
    validate({ it <= most }, lazyMessage)

inline fun <T : Comparable<T>> T.validateLessThan(bound: T, lazyMessage: () -> Any = { "Should be less than '$bound' but was '$this'" }) =
    validate({ it < bound }, lazyMessage)

inline fun <T : Comparable<T>> T.validateBetween(least: T, most: T, lazyMessage: () -> Any = { "Should be between '$least' and '$most' but was '$this'" }) =
    validate({ it in least..most }, lazyMessage)

inline fun <T : Comparable<T>> T.validateIn(range: ClosedRange<T>, lazyMessage: () -> Any = { "Should be in $range but was '$this'" }) =
    validate({ it in range }, lazyMessage)

inline fun <T : Number> T.validateZero(lazyMessage: () -> Any = { "Should be zero but was '$this'" }) =
    validate({ it.toDouble() == 0.0 }, lazyMessage)

inline fun <T : Number> T.validateNotZero(lazyMessage: () -> Any = { "Should not be zero" }) =
    validate({ it.toDouble() != 0.0 }, lazyMessage)
