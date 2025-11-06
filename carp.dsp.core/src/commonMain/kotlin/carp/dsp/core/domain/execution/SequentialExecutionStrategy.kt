package carp.dsp.core.domain.execution

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.domain.data.CarpTabularData
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
                else -> throw IllegalArgumentException("Unsupported process type: ${process::class.simpleName}")
            }
        }

        println("Workflow execution completed successfully.")
    }

    /**
     * Recursively flattens all workflow components to a list of steps in execution order.
     */
    private fun flattenSteps( component: WorkflowComponent): List<Step> = when (component)
    {
        is Step -> listOf(component)
        is Workflow -> component.getComponents().flatMap { flattenSteps(it) }
        else -> error("Unknown component type: ${component::class.simpleName}")
    }

    private fun resolveInputData(step: Step): CarpTabularData
    {
        // For now: Assume first inputData is what we fetch
        if (step.inputData?.isEmpty() != false)
        {
            println("No input data defined for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

        val inputName = step.inputData?.firstOrNull()?.name
        if (inputName == null) {
            println("No input data name found for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

        return when (val handle = dataRegistry.resolve(inputName)) {
            is InMemoryData -> {
                // Try to extract DataStreamBatch from InMemoryData and convert to CarpTabularData
                when (val data = handle.dataset) {
                    is DataStreamBatch -> dataConverter.toTabularData(data)
                    else -> {
                        println(
                            "Warning: Input data '$inputName' is not a DataStreamBatch or CarpTabularData," +
                                    " creating empty dataset."
                        )
                        CarpTabularData(emptyList(), MutableDataStreamBatch())
                    }
                }
            }
            else -> {
                error("Input data '$inputName' is not in memory or has wrong type.")
            }
        }
    }

    private fun registerOutputData(step: Step, output: ICarpTabularData)
    {
        val outputData = step.outputData
        if (outputData == null)
        {
            println("No output data reference defined for step '${step.metadata.name}', output not stored.")
            return
        }
        val outputName = outputData.name
        dataRegistry.register(outputName, InMemoryData(output))
        println("Registered output data under name: '$outputName'")
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

            if (outputDataSet != null && step.outputData != null)
            {
                registerOutputData(step, outputDataSet)
            }
            else if (outputDataSet == null)
            {
                println("AnalysisProcess '${process.name}' produced no output.")
            }
        }
        catch ( e: IllegalArgumentException )
        {
            println("Invalid arguments for AnalysisProcess: ${process.name}")
            throw e
        }
        catch ( e: IllegalStateException )
        {
            println("Invalid state during AnalysisProcess execution: ${process.name}")
            throw e
        }
    }
}
