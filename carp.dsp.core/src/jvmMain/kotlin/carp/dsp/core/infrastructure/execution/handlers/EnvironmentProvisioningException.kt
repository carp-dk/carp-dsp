package carp.dsp.core.infrastructure.execution.handlers

/**
 * Raised when an environment cannot be provisioned or validated.
 */
class EnvironmentProvisioningException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

