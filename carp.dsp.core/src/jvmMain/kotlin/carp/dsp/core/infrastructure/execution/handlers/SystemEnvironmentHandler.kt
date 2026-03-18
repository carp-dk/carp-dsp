package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler

/**
 * No-op handler for system environments.
 *
 * System environment is always available.
 * No setup, teardown, or validation needed.
 */
class SystemEnvironmentHandler : EnvironmentHandler {

    override fun canHandle(environmentRef: EnvironmentRef): Boolean {
        return environmentRef is SystemEnvironmentRef
    }

    override fun setup(environmentRef: EnvironmentRef): Boolean {
        // No-op: system is always available
        return true
    }

    override fun generateExecutionCommand(
        environmentRef: EnvironmentRef,
        command: String
    ): String {
        // No wrapping: use command as-is
        return command
    }

    override fun teardown(environmentRef: EnvironmentRef): Boolean {
        // No-op: nothing to clean up
        return true
    }

    override fun validate(environmentRef: EnvironmentRef): Boolean {
        // System is always valid
        return true
    }
}
