package com.github.marcoral.versatia.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

/**
 * The one place the processor reports problems through: every collector fails through it, the
 * processor checks [hadErrors] once, and the same message at the same declaration is logged once
 * however many usages lead to it.
 */
internal class Reporter(private val logger: KSPLogger) {
    private val reported = mutableSetOf<Pair<KSNode?, String>>()

    var hadErrors = false
        private set

    /** Logs [message] at [node] and returns null, so a collector can `return fail(...)`. */
    fun fail(node: KSNode?, message: String): Nothing? {
        hadErrors = true
        if (reported.add(node to message)) logger.error(message, node)
        return null
    }
}
