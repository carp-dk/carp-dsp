package carp.dsp.core.domain.execution

import carp.dsp.core.domain.data.CarpMeasurementMetadata
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.StepCountMeasurementRow
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.application.data.InMemoryData
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import dk.cachet.carp.analytics.domain.process.AnalysisProcess
import dk.cachet.carp.analytics.domain.process.ExternalProcess
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SequentialExecutionStrategyTest {

    private fun createTestDataRegistry(): DataRegistry {
        return DataRegistry()
    }

    private fun createTestTabularData(): CarpTabularData {
        val studyDeploymentId = UUID.randomUUID()
        val dataStreamId = DataStreamId(studyDeploymentId, "phone", DataType("test.data", "step_count"))
        val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1000L), 1L)

        val metadata = CarpMeasurementMetadata(
            sequenceIndex = 0,
            measurementIndex = 0,
            dataStreamId = dataStreamId,
            firstSequenceId = 100L,
            triggerIds = listOf(1),
            syncPoint = syncPoint
        )

        val stepCountRow = StepCountMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = null,
            dataType = DataType("test.data", "step_count"),
            metadata = metadata,
            stepCount = StepCount(1500)
        )

        return CarpTabularData(
            rows = listOf(stepCountRow),
            originalBatch = MutableDataStreamBatch()
        )
    }

    private fun createMockAnalysisProcess(): AnalysisProcess {
        return object : AnalysisProcess {
            override val name: String = "TestAnalysisProcess"
            override val description: String = "Test analysis process"

            override fun process(input: dk.cachet.carp.analytics.domain.data.ICarpTabularData): dk.cachet.carp.analytics.domain.data.ICarpTabularData {
                return input
            }
        }
    }

    private fun createMockExternalProcess(): ExternalProcess {
        return object : ExternalProcess {
            override val name: String = "TestExternalProcess"
            override val description: String = "Test external process"
            override val executionContext: ExecutionContext = ExecutionContext(null)
            override fun getArguments(): Any = emptyMap<String, Any>()
        }
    }

    private fun createMockExecutorFactory(): ExecutionFactory {
        return ExecutionFactory()
    }

    @Test
    fun testExecute_EmptyWorkflow() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val workflow = Workflow(
            WorkflowMetadata(
                "Test Workflow",
                "A test workflow",
                UUID.randomUUID()
            )
        )

        // Should execute without error even with empty workflow
        strategy.execute(workflow, executorFactory)
    }

    @Test
    fun testExecute_WorkflowWithAnalysisProcess() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Register test data in the registry
        val testData = createTestTabularData()
        dataRegistry.register("input_data", InMemoryData(testData))

        // Create a step with the analysis process
        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Test Step", description = "A test step"),
            inputs = listOf(
                InputDataSpec(
                    identifier = "input_data",
                    name = "Test Input",
                    source = InMemorySource(registryKey = "input_data")
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "output_data",
                    name = "Test Output",
                    destination = RegistryDestination(key = "output_data")
                )
            ),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "Test Workflow",
                "A test workflow with analysis process",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step)

        // Execute the workflow
        strategy.execute(workflow, executorFactory)

        // Verify that output data was registered
        val outputHandle = dataRegistry.resolve("output_data")
        assertTrue(outputHandle is InMemoryData)
    }

    @Test
    fun testExecute_WorkflowWithExternalProcess() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create a step with external process
        val externalProcess = createMockExternalProcess()
        val step = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "External Step", description = "External step test"),
            process = externalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "Test Workflow",
                "A test workflow with external process",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step)

        val mockExecutor = {
            object : dk.cachet.carp.analytics.domain.execution.Executor<ExternalProcess> {
                override fun execute(process: ExternalProcess, context: ExecutionContext)
                {
                    // Mock execution logic for external process
                    println("Mock executing external process: ${process.name}")
                }
            }
        }

        executorFactory.register(externalProcess::class, mockExecutor)

        // Should execute without error
        strategy.execute(workflow, executorFactory)
    }

    @Test
    fun testExecute_UnsupportedProcessType() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create a step with unsupported process type
        val unsupportedProcess = object : dk.cachet.carp.analytics.domain.process.WorkflowProcess {
            override val name: String = "UnsupportedProcess"
            override val description: String = "Unsupported process type"
        }

        val step = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Unsupported Step", description = "Test unsupported process"),
            process = unsupportedProcess
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "Test Workflow",
                "A test workflow with unsupported process",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step)

        // Should throw exception for unsupported process type
        assertFailsWith<IllegalArgumentException> {
            strategy.execute(workflow, executorFactory)
        }
    }

    @Test
    fun testExecute_NestedWorkflow() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create nested workflow structure
        val innerAnalysisProcess = createMockAnalysisProcess()
        val innerStep = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Inner Step", description = "Inner step test"),
            process = innerAnalysisProcess
        )

        val innerWorkflow = Workflow(
            WorkflowMetadata(
                "Inner Workflow",
                "A nested workflow",
                UUID.randomUUID()
            )
        )
        innerWorkflow.addComponent(innerStep)

        val outerWorkflow = Workflow(
            WorkflowMetadata(
                "Outer Workflow",
                "Main workflow containing nested workflow",
                UUID.randomUUID()
            )
        )
        outerWorkflow.addComponent(innerWorkflow)

        // Should execute nested workflow structure
        strategy.execute(outerWorkflow, executorFactory)
    }

    @Test
    fun testExecute_MultipleSteps() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Register test data
        val testData = createTestTabularData()
        dataRegistry.register("input_data", InMemoryData(testData))

        // Create multiple steps
        val analysisProcess1 = createMockAnalysisProcess()
        val step1 = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "First Step", description = "Step 1"),
            inputs = listOf(
                InputDataSpec(
                    identifier = "input_data",
                    name = "Input Data",
                    source = InMemorySource(registryKey = "input_data")
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "intermediate_data",
                    name = "Intermediate Data",
                    destination = RegistryDestination(key = "intermediate_data")
                )
            ),
            process = analysisProcess1
        )

        val analysisProcess2 = createMockAnalysisProcess()
        val step2 = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Second Step", description = "Step 2"),
            inputs = listOf(
                InputDataSpec(
                    identifier = "intermediate_data",
                    name = "Intermediate Data",
                    source = InMemorySource(registryKey = "intermediate_data")
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "final_data",
                    name = "Final Data",
                    destination = RegistryDestination(key = "final_data")
                )
            ),
            process = analysisProcess2
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "Multi-Step Workflow",
                "Workflow with chained steps",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step1)
        workflow.addComponent(step2)

        // Execute workflow with multiple steps
        strategy.execute(workflow, executorFactory)

        // Verify that both intermediate and final data were registered
        assertTrue(dataRegistry.resolve("intermediate_data") is InMemoryData)
        assertTrue(dataRegistry.resolve("final_data") is InMemoryData)
    }

    @Test
    fun testExecute_StepWithoutInputData() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create step without input data
        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Step Without Input", description = "No input test"),
            inputs = emptyList(), // No input data
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "No Input Workflow",
                "Workflow with step that has no input",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step)

        // Should execute without error, using empty dataset
        strategy.execute(workflow, executorFactory)
    }

    @Test
    fun testExecute_StepWithoutOutputData() {
        val dataRegistry = createTestDataRegistry()
        val strategy = SequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Register test data
        val testData = createTestTabularData()
        dataRegistry.register("input_data", InMemoryData(testData))

        // Create step without output data
        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata(id = UUID.randomUUID(), name = "Step Without Output", description = "No output test"),
            inputs = listOf(
                InputDataSpec(
                    identifier = "input_data",
                    name = "Input Data",
                    source = InMemorySource(registryKey = "input_data")
                )
            ),
            outputs = emptyList(), // No output data
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata(
                "No Output Workflow",
                "Workflow with step that has no output",
                UUID.randomUUID()
            )
        )
        workflow.addComponent(step)

        // Should execute without error, output not stored
        strategy.execute(workflow, executorFactory)
    }
}
