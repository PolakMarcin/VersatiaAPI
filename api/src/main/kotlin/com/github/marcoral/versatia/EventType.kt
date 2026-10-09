package com.github.marcoral.versatia

/** A moment in a plugin's life at which the runtime walks its descriptor. */
enum class LifecycleEvent { START, RELOAD, STOP }

/**
 * When an enhancement is executed. Declared on the enhancement annotation, either directly or
 * through a property of the annotation; see docs/enhancements.md.
 */
enum class EventType(private val events: Set<LifecycleEvent>) {
    /** Technical "no value": annotations cannot hold `null`. Never present in a descriptor. */
    _NULL(emptySet()),
    PLUGIN_RELOAD_OR_START(setOf(LifecycleEvent.START, LifecycleEvent.RELOAD)),
    PLUGIN_START_ONLY(setOf(LifecycleEvent.START)),
    PLUGIN_RELOAD_ONLY(setOf(LifecycleEvent.RELOAD)),
    PLUGIN_STOP(setOf(LifecycleEvent.STOP)),
    ;

    fun appliesTo(event: LifecycleEvent) = event in events
}
