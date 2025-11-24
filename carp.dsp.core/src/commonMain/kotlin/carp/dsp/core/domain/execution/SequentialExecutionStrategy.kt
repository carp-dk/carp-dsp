package carp.dsp.core.domain.execution

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.process.DataRetrievalProcess
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.application.data.InMemoryData
import dk.cachet.carp.analytics.domain.data.ICarpTabularData
import dk.cachet.carp.analytics.domain.execution.ExecutionStrategy
import dk.cachet.carp.analytics.domain.execution.IExecutionFactory
import dk.cachet.carp.analytics.domain.process.AnalysisProcess
import dk.cachet.carp.analytics.domain.process.ExternalProcess
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowComponent
import dk.cachet.carp.data.application.DataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamBatch

/**
 * Executes workflow steps sequentially using a DataRegistry for data management.
 * Uses CarpTabularData for modern data handling instead of deprecated CollectedDataSet.
 */
class SequentialExecutionStrategy(
    private val dataRegistry: DataRegistry,
    private val dataConverter: DataStreamBatchConverter = DataStreamBatchConverter()
) : ExecutionStrategy
{
    /**
     * Executes the provided steps in the workflow one by one using the given ExecutorFactory.
     *
     * @param workflow The workflow containing the steps to execute.
     * @param executionFactory The factory for creating executors for each process type.
     */
    override fun execute(workflow: Workflow, executionFactory: IExecutionFactory)
    {
        println("Starting sequential execution of workflow: ${workflow.metadata.name}")

        val steps = flattenSteps(workflow)
        for ((index, step) in steps.withIndex())
        {
            println("Running step ${index + 1}/${steps.size}: ${step.metadata.name}")

            when (val process = step.process) {
                is ExternalProcess -> handleExternalProcess(process, executionFactory)
                is AnalysisProcess -> handleAnalysisProcess(step, process)
                is DataRetrievalProcess -> handleDataRetrievalProcess(step, process)
                else -> throw IllegalArgumentException("Unsupported process type: ${process::class.simpleName}")
            }
        }

        println("Workflow execution completed successfully.")
    }

    /**
     * Recursively flattens all workflow components to a list of steps in execution order.
     */
    private fun flattenSteps(component: WorkflowComponent): List<Step> = when (component)
    {
        is Step -> listOf(component)
        is Workflow -> component.getComponents().flatMap { flattenSteps(it) }
        else -> error("Unknown component type: ${component::class.simpleName}")
    }

    /**
     * Resolves input data for a step from the registry.
     * Uses the new InputDataSpec model with inputs property.
     */
    private fun resolveInputData(step: Step): CarpTabularData
    {
        // Check if step has inputs defined
        if (step.inputs.isEmpty())
        {
            println("No input data defined for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

        // Get the first input specification
        val inputSpec = step.inputs.firstOrNull()
        if (inputSpec == null) {
            println("No input data spec found for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

        // Extract the registry key from InMemorySource
        val registryKey = when (val source = inputSpec.source) {
            is dk.cachet.carp.analytics.domain.data.InMemorySource -> source.registryKey
            else -> {
                println(
                    "Warning: Input source for '${inputSpec.identifier}' is not InMemorySource, using empty dataset."
                )
                return CarpTabularData(emptyList(), MutableDataStreamBatch())
            }
        }

        return when (val handle = dataRegistry.resolve(registryKey)) {
            is InMemoryData -> {
                // Try to extract DataStreamBatch from InMemoryData and convert to CarpTabularData
                when (val data = handle.dataset) {
                    is DataStreamBatch -> dataConverter.toTabularData(data)
                    else -> {
                        println(
                            "Warning: Input data '$registryKey' is not a DataStreamBatch or CarpTabularData," +
                                    " creating empty dataset."
                        )
                        CarpTabularData(emptyList(), MutableDataStreamBatch())
                    }
                }
            }
            else -> {
                error("Input data '$registryKey' is not in memory or has wrong type.")
            }
        }
    }

    /**
     * Registers output data from a step to the registry.
     * Uses the new OutputDataSpec model with outputs property.
     */
    private fun registerOutputData(step: Step, output: ICarpTabularData)
    {
        // Check if step has outputs defined
        if (step.outputs.isEmpty())
        {
            println("No output data reference defined for step '${step.metadata.name}', output not stored.")
            return
        }

        // Get the first output specification
        val outputSpec = step.outputs.firstOrNull()
        if (outputSpec == null) {
            println("No output spec found for step '${step.metadata.name}', output not stored.")
            return
        }

        // Extract the registry key from RegistryDestination
        val registryKey = when (val destination = outputSpec.destination) {
            is dk.cachet.carp.analytics.domain.data.RegistryDestination -> destination.key
            else -> {
                println(
                    "Warning: Output destination for '${outputSpec.identifier}' " +
                        "is not RegistryDestination, output not stored."
                )
                return
            }
        }

        dataRegistry.register(registryKey, InMemoryData(output))
        println("Registered output data under name: '$registryKey'")
    }

    /**
     * Handles the execution of an [ExternalProcess] using the provided [ExecutionFactory].
     * Sets up the executor, executes the process, and cleans up afterward.
     */
    private fun handleExternalProcess(process: ExternalProcess, executorFactory: IExecutionFactory)
    {
        val executor = executorFactory.getExecutor(process)
        try
        {
            executor.setup(process, process.executionContext)
            println("Executing ExternalProcess: ${process.name}")
            executor.execute(process, process.executionContext)
        }
        catch (e: IllegalArgumentException)
        {
            println("Invalid arguments for ExternalProcess: ${process.name}")
            throw e
        }
        catch (e: IllegalStateException)
        {
            println("Invalid state during ExternalProcess execution: ${process.name}")
            throw e
        }
        finally
        {
            println("Cleaning up ExternalProcess: ${process.name}")
            executor.cleanup(process, process.executionContext)
        }
    }

    /**
     * Handles the execution of an [AnalysisProcess].
     * Resolves input data, executes the process, and registers output data if applicable.
     */
    private fun handleAnalysisProcess(step: Step, process: AnalysisProcess)
    {
        try
        {
            println("Executing AnalysisProcess: ${process.name}")
            val inputDataSet = resolveInputData(step)

            val outputDataSet = process.process(inputDataSet)

            if (outputDataSet != null && step.outputs.isNotEmpty())
            {
                registerOutputData(step, outputDataSet)
            }
            else if (outputDataSet == null)
            {
                println("AnalysisProcess '${process.name}' produced no output.")
            }
        }
        catch (e: IllegalArgumentException)
        {
            println("Invalid arguments for AnalysisProcess: ${process.name}")
            throw e
        }
        catch (e: IllegalStateException)
        {
            println("Invalid state during AnalysisProcess execution: ${process.name}")
            throw e
        }
    }

    /**
     * Handles the execution of a [DataRetrievalProcess].
     * Data retrieval processes fetch data from external sources and don't require input data.
     */
    private fun handleDataRetrievalProcess(step: Step, process: DataRetrievalProcess)
    {
        try
        {
            println("Executing DataRetrievalProcess: ${process.name}")
            println("  - Description: ${process.description}")

            // Note: Actual execution requires platform-specific executor factory
            // This is a placeholder that shows what would be retrieved
            if (step.outputs.isNotEmpty())
            {
                println("  - Would produce ${step.outputs.size} output(s)")
                step.outputs.forEach { outputSpec ->
                    println("    - ${outputSpec.identifier}: ${outputSpec.name}")
                }
            }

            println("  ⚠️  Note: HTTP download not executed (executor requires JVM platform)")
            println("  To enable downloads, use JVM-specific executor factory")
        }
        catch (e: IllegalArgumentException)
        {
            println("Invalid arguments for DataRetrievalProcess: ${process.name}")
            throw e
        }
        catch (e: IllegalStateException)
        {
            println("Invalid state during DataRetrievalProcess execution: ${process.name}")
            throw e
        }
    }
}

