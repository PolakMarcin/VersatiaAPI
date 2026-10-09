package com.github.marcoral.versatia.lifecycle

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement

/**
 * Calls the annotated function at [phase]. Parameters are injected like bean dependencies. The
 * function must belong to the plugin's main class, an `object`, or a bean. See docs/enhancements.md.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@VersatiaEnhancement(LifecycleProcessors.AUTO_INVOKE, enhancementPhaseParamRefName = "phase")
annotation class AutoInvoke(val phase: EventType)

/** The VersatiaCore processors of this package, by name: the API does not see the runtime. */
object LifecycleProcessors {
    const val AUTO_INVOKE = "com.github.marcoral.versatia.lifecycle.AutoInvokeEnhancementProcessor"
}
