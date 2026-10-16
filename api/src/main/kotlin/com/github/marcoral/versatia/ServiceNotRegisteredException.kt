package com.github.marcoral.versatia

/** Thrown by `VersatiaEngine.loadService` when no plugin provides the service. */
class ServiceNotRegisteredException(service: Class<*>) :
    IllegalStateException("No provider of ${service.name} is registered. Is the plugin providing it installed, enabled and listed under depend: or softdepend:?")
