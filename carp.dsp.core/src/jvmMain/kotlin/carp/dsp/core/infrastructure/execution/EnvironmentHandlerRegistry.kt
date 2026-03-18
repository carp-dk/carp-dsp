package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.execution.handlers.CondaEnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.PixiEnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.REnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.SystemEnvironmentHandler
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler

/**
 * Routes EnvironmentRef to appropriate handler.
 */
object EnvironmentHandlerRegistry {

    private val handlers: List<EnvironmentHandler> = listOf(
        CondaEnvironmentHandler(),
        PixiEnvironmentHandler(),
        REnvironmentHandler(),
        SystemEnvironmentHandler()

    )

    /**
     * Get the handler for an environment reference.
     *
     * @throws IllegalArgumentException if no handler found
     */
    fun getHandler(environmentRef: EnvironmentRef): EnvironmentHandler {
        return handlers.find { it.canHandle(environmentRef) }
            ?: throw IllegalArgumentException(
                "No handler for environment type: ${environmentRef::class.simpleName}"
            )
    }
}
