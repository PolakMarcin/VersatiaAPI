package com.github.marcoral.versatia.ksp.testscopes

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement
import com.github.marcoral.versatia.di.Bean
import com.github.marcoral.versatia.di.BeanProcessors

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
