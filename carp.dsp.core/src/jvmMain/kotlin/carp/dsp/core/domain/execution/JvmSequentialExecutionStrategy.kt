package carp.dsp.core.domain.execution

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.process.DataRetrievalProcess
import carp.dsp.core.infrastructure.process.DataRetrievalExecutorFactory
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
import kotlinx.coroutines.runBlocking

/**
 * JVM-specific execution strategy with support for data retrieval processes.
 * This extends the common strategy with actual HTTP download capabilities.
 */
class JvmSequentialExecutionStrategy(
    private val dataRegistry: DataRegistry,
    private val dataConverter: DataStreamBatchConverter = DataStreamBatchConverter(),
    private val retrievalExecutorFactory: DataRetrievalExecutorFactory = DataRetrievalExecutorFactory()
) : ExecutionStrategy {

    override fun execute(workflow: Workflow, executionFactory: IExecutionFactory) {
        println("Starting sequential execution of workflow: ${workflow.metadata.name}")

        val steps = flattenSteps(workflow)
        for ((index, step) in steps.withIndex()) {
            println("Running step ${index + 1}/${steps.size}: ${step.metadata.name}")

            when (val process = step.process) {
                is ExternalProcess -> handleExternalProcess(step, process, executionFactory)
                is AnalysisProcess -> handleAnalysisProcess(step, process)
                is DataRetrievalProcess -> handleDataRetrievalProcess(step, process)
                else -> throw IllegalArgumentException("Unsupported process type: ${process::class.simpleName}")
            }
        }

        println("Workflow execution completed successfully.")

        // Clean up resources
        retrievalExecutorFactory.close()
    }

    private fun flattenSteps(component: WorkflowComponent): List<Step> = when (component) {
        is Step -> listOf(component)
        is Workflow -> component.getComponents().flatMap { flattenSteps(it) }
        else -> error("Unknown component type: ${component::class.simpleName}")
    }

    private fun resolveInputData(step: Step): CarpTabularData {
        if (step.inputs.isEmpty()) {
            println("No input data defined for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

        val inputSpec = step.inputs.firstOrNull()
        if (inputSpec == null) {
            println("No input data spec found for step '${step.metadata.name}', using empty dataset.")
            return CarpTabularData(emptyList(), MutableDataStreamBatch())
        }

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
                when (val data = handle.dataset) {
                    is DataStreamBatch -> dataConverter.toTabularData(data)
                    else -> {
                        println("Warning: Input data '$registryKey' is not a DataStreamBatch, creating empty dataset.")
                        CarpTabularData(emptyList(), MutableDataStreamBatch())
                    }
                }
            }
            else -> {
                error("Input data '$registryKey' is not in memory or has wrong type.")
            }
        }
    }

    private fun registerOutputData(step: Step, output: ICarpTabularData) {
        if (step.outputs.isEmpty()) {
            println("No output data reference defined for step '${step.metadata.name}', output not stored.")
            return
        }

        val outputSpec = step.outputs.firstOrNull()
        if (outputSpec == null) {
            println("No output spec found for step '${step.metadata.name}', output not stored.")
            return
        }

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

    private fun handleExternalProcess(step: Step, process: ExternalProcess, executorFactory: IExecutionFactory) {
        val executor = executorFactory.getExecutor(process)
        try {
            // Resolve data bindings for PythonProcess
            if (process is carp.dsp.core.application.process.PythonProcess) {
                process.resolveBindings(step.inputs, step.outputs, dataRegistry)
            }

            executor.setup(step)
            println("Executing ExternalProcess: ${process.name}")
            executor.execute(step)
        } catch (e: IllegalArgumentException) {
            println("Invalid arguments for ExternalProcess: ${process.name}")
            throw e
        } catch (e: IllegalStateException) {
            println("Invalid state during ExternalProcess execution: ${process.name}")
            throw e
        } finally {
            println("Finished ExternalProcess: ${process.name}")
        }
    }

    private fun handleAnalysisProcess(step: Step, process: AnalysisProcess) {
        try {
            println("Executing AnalysisProcess: ${process.name}")
            val inputDataSet = resolveInputData(step)
            val outputDataSet = process.process(inputDataSet)

            if (outputDataSet != null && step.outputs.isNotEmpty()) {
                registerOutputData(step, outputDataSet)
            } else if (outputDataSet == null) {
                println("AnalysisProcess '${process.name}' produced no output.")
            }
        } catch (e: IllegalArgumentException) {
            println("Invalid arguments for AnalysisProcess: ${process.name}")
            throw e
        } catch (e: IllegalStateException) {
            println("Invalid state during AnalysisProcess execution: ${process.name}")
            throw e
        }
    }

    private fun handleDataRetrievalProcess(step: Step, process: DataRetrievalProcess) {
        try {
            println("Executing DataRetrievalProcess: ${process.name}")
            println("  - Description: ${process.description}")

            val executor = retrievalExecutorFactory.getExecutor(process)
            val outputPath = resolveOutputPath(step)

            println()
            val executionOutputs = runBlocking {
                executor.execute(process, outputPath)
            }
            println()

            reportRetrievalResults(executionOutputs)
        } catch (e: IllegalArgumentException) {
            println("Invalid arguments for DataRetrievalProcess: ${process.name}")
            throw e
        } catch (e: IllegalStateException) {
            println("Invalid state during DataRetrievalProcess execution: ${process.name}")
            throw e
        }
    }

    /**
     * Resolves the output path from step outputs.
     */
    private fun resolveOutputPath(step: Step): String {
        val outputSpec = step.outputs.firstOrNull() ?: return "/tmp/dsp-downloads"

        return when (val dest = outputSpec.destination) {
            is dk.cachet.carp.analytics.domain.data.FileDestination -> {
                extractBasePath(dest.path)
            }
            else -> "/tmp/dsp-downloads"
        }
    }

    /**
     * Extracts base directory path by going up two levels from the file path.
     */
    private fun extractBasePath(fullPath: String): String {
        val lastSlash = fullPath.lastIndexOf('/')
        if (lastSlash <= 0) {
            return "."
        }

        val parentPath = fullPath.take(lastSlash)
        val secondLastSlash = parentPath.lastIndexOf('/')

        return if (secondLastSlash > 0) {
            parentPath.take(secondLastSlash)
        } else {
            parentPath
        }
    }

    /**
     * Reports the results of data retrieval execution.
     */
    private fun reportRetrievalResults(executionOutputs: List<dk.cachet.carp.analytics.domain.data.ExecutionOutput>) {
        val successful = executionOutputs.count { it.success }
        val failed = executionOutputs.count { !it.success }

        println("  ✅ Successfully retrieved $successful file(s)")
        if (failed > 0) {
            println("  ❌ Failed to retrieve $failed file(s)")
        }
    }
}
