package com.github.marcoral.versatia

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceNotRegisteredExceptionTest {
    @Test
    fun `names the service and the fix`() = assertEquals(
        "No provider of java.lang.Runnable is registered. Is the plugin providing it installed, enabled and listed under depend: or softdepend:?",
        ServiceNotRegisteredException(Runnable::class.java).message,
    )
}
