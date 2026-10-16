package com.github.marcoral.versatia.ksp.testscopes

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.ExposeSingleton
import com.github.marcoral.versatia.di.LocalBean

/**
 * Scopes that reach the processor as compiled classes (through the test classpath), the way the
 * scopes of VersatiaAPI itself do. Repeated `@VersatiaEnhancement`s are then packed into a
 * `Container` annotation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = EventType.PLUGIN_START_ONLY)
@VersatiaEnhancement("com.example.test.AuditEnhancementProcessor", enhancementPhase = EventType.PLUGIN_STOP)
annotation class AuditedBean

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement("com.example.test.PhasedEnhancementProcessor", enhancementPhaseParamRefName = "phase")
annotation class PhasedBean(val phase: EventType)

/** A broken scope without a source location: its errors point at the class that uses it. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement("", enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class BrokenBean

/** A final class another plugin exposes, as it reaches a dependent plugin. */
@ExposeSingleton
class SharedClock

/** A final class that is a local bean of the plugin declaring it, so no other plugin ever receives it. */
@LocalBean
class PrivateClock
