package carp.dsp.core.domain.execution

import dk.cachet.carp.analytics.domain.execution.Executor
import dk.cachet.carp.analytics.domain.execution.IExecutionFactory
import dk.cachet.carp.analytics.domain.process.ExternalProcess
import kotlin.reflect.KClass

/**
 * Factory for creating Executor instances dynamically using a registration-based model.
 */
class ExecutionFactory : IExecutionFactory
{
    private val registry: MutableMap<KClass<out ExternalProcess>, () -> Executor> = mutableMapOf()

    /**
     * Registers an Executor for a specific Process type.
     * @param processType The class of the process.
     * @param executorCreator A lambda that creates an Executor instance.
     */
    override fun <P : ExternalProcess> register( processType: KClass<out P>, executorCreator: () -> Executor )
    {
        registry[processType] = executorCreator
    }

    /**
     * Retrieves an Executor for the given Process.
     * @param process The process instance.
     * @return The corresponding [Executor] instance.
     * @throws IllegalArgumentException If no Executor is registered for the given Process type.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <P : ExternalProcess> getExecutor( process: P ): Executor
    {
        val creator = registry[process::class]
            ?: throw IllegalArgumentException("Unsupported process type: ${process::class.simpleName}")
        return creator()
    }
}
