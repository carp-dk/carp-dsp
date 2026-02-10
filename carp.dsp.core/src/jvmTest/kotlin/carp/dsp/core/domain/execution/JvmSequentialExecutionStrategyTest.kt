package carp.dsp.core.domain.execution

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.data.CarpMeasurementMetadata
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.StepCountMeasurementRow
import carp.dsp.core.infrastructure.process.DataRetrievalExecutorFactory
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.application.data.InMemoryData
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.ICarpTabularData
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.execution.IExecutionFactory
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class JvmSequentialExecutionStrategyTest {

    private lateinit var dataRegistry: DataRegistry
    private lateinit var dataConverter: DataStreamBatchConverter
    private lateinit var outputCapture: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream

    @Before
    fun setUp() {
        dataRegistry = DataRegistry()
        dataConverter = DataStreamBatchConverter()

        // Capture console output for verification
        outputCapture = ByteArrayOutputStream()
        originalOut = System.out
        System.setOut(PrintStream(outputCapture))
    }

    @After
    fun tearDown() {
        // Restore original System.out
        System.setOut(originalOut)
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

    private fun createMockAnalysisProcess(returnNull: Boolean = false): AnalysisProcess {
        return object : AnalysisProcess {
            override val name: String = "TestAnalysisProcess"
            override val description: String = "Test analysis process"

            override fun process(input: ICarpTabularData): ICarpTabularData? {
                return if (returnNull) null else input
            }
        }
    }

    private fun createMockExternalProcess(): ExternalProcess = object : ExternalProcess {
        override val name: String = "TestExternalProcess"
        override val description: String = "Test external process"
        override fun getArguments(): Any = emptyMap<String, Any>()
    }

    private fun createMockExecutorFactory(shouldSucceed: Boolean = true): IExecutionFactory = object : IExecutionFactory {
        override fun <P : ExternalProcess> register(
            processType: kotlin.reflect.KClass<out P>,
            executorCreator: () -> dk.cachet.carp.analytics.domain.execution.Executor
        ) { /* no-op for tests */ }

        override fun <P : ExternalProcess> getExecutor(process: P): dk.cachet.carp.analytics.domain.execution.Executor {
            return object : dk.cachet.carp.analytics.domain.execution.Executor {
                override fun setup(step: Step) { /* no-op */ }
                override fun execute(step: Step) {
                    if (!shouldSucceed) error("Mock execution failure")
                }
            }
        }
    }

    @Test
    fun testConstruction_WithDefaults() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)

        assertNotNull(strategy)
    }

    @Test
    fun testConstruction_WithCustomComponents() {
        val customConverter = DataStreamBatchConverter()
        val customFactory = DataRetrievalExecutorFactory()

        val strategy = JvmSequentialExecutionStrategy(
            dataRegistry = dataRegistry,
            dataConverter = customConverter,
            retrievalExecutorFactory = customFactory
        )

        assertNotNull(strategy)
    }

    @Test
    fun testExecute_EmptyWorkflow() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val workflow = Workflow(
            WorkflowMetadata(
                "Empty Workflow",
                "A workflow with no steps",
                UUID.randomUUID()
            )
        )

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Starting sequential execution"))
        assertTrue(output.contains("Workflow execution completed successfully"))
    }

    @Test
    fun testExecute_SingleAnalysisProcess() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Prepare input data
        val inputData = createTestTabularData()
        dataRegistry.register("input-data", InMemoryData(inputData))

        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata("Analysis Step", UUID.randomUUID(), "Test step"),
            inputs = listOf(InputDataSpec("input", "input", source = InMemorySource("input-data"))),
            outputs = listOf(OutputDataSpec("output", "output", destination = RegistryDestination("output-data"))),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID())
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing AnalysisProcess"))
        assertTrue(output.contains("TestAnalysisProcess"))
        assertTrue(output.contains("Registered output data under name: 'output-data'"))

        // Verify output was registered
        val outputHandle = dataRegistry.resolve("output-data")
        assertNotNull(outputHandle)
    }

    @Test
    fun testExecute_AnalysisProcessWithNoOutput() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val analysisProcess = createMockAnalysisProcess(returnNull = true)
        val step = Step(
            metadata = StepMetadata("Analysis Step", UUID.randomUUID(), "Test step"),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("produced no output"))
    }

    @Test
    fun testExecute_MultipleSteps() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create multiple steps
        val step1 = Step(
            metadata = StepMetadata("Step 1", UUID.randomUUID(), "First step"),
            process = createMockAnalysisProcess()
        )

        val step2 = Step(
            metadata = StepMetadata("Step 2", UUID.randomUUID(), "Second step"),
            process = createMockAnalysisProcess()
        )

        val workflow = Workflow(
            WorkflowMetadata("Multi-Step Workflow", "A workflow with multiple steps", UUID.randomUUID())
        )
        workflow.addComponent(step1)
        workflow.addComponent(step2)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Running step 1/2"))
        assertTrue(output.contains("Running step 2/2"))
    }

    @Test
    fun testExecute_NestedWorkflow() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val innerStep = Step(
            metadata = StepMetadata("Inner Step", UUID.randomUUID(), "Inner step"),
            process = createMockAnalysisProcess()
        )

        val innerWorkflow = Workflow(
            WorkflowMetadata("Inner Workflow", "Inner workflow", UUID.randomUUID())
        )
        innerWorkflow.addComponent(innerStep)

        val outerWorkflow = Workflow(
            WorkflowMetadata("Outer Workflow", "Outer workflow", UUID.randomUUID())
        )
        outerWorkflow.addComponent(innerWorkflow)

        strategy.execute(outerWorkflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Starting sequential execution"))
        assertTrue(output.contains("Inner Step"))
    }

    @Test
    fun testExecute_ExternalProcess() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val externalProcess = createMockExternalProcess()
        val step = Step(
            metadata = StepMetadata("External Step", UUID.randomUUID(), "Test external step"),
            process = externalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing ExternalProcess"))
        assertTrue(output.contains("TestExternalProcess"))
        assertTrue(output.contains("Finished ExternalProcess"))
    }

    @Test
    fun testExecute_ExternalProcessFailure() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory(shouldSucceed = false)

        val externalProcess = createMockExternalProcess()
        val step = Step(
            metadata = StepMetadata("Failing Step", UUID.randomUUID(), "Test failing step"),
            process = externalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        assertFailsWith<IllegalStateException> {
            strategy.execute(workflow, executorFactory)
        }

        val output = outputCapture.toString()
        assertTrue(output.contains("Invalid state during ExternalProcess execution"))
    }

    @Test
    fun testResolveInputData_NoInput() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata("No Input Step", UUID.randomUUID(), "Test step"),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("No input data defined"))
    }

    @Test
    fun testResolveInputData_ValidInput() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Register input data
        val inputData = createTestTabularData()
        dataRegistry.register("test-input", InMemoryData(inputData))

        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata("Valid Input Step", UUID.randomUUID(), "Test step"),
            inputs = listOf(InputDataSpec("input", "input", source = InMemorySource("test-input"))),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing AnalysisProcess"))
    }

    @Test
    fun testRegisterOutputData_NoOutput() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val analysisProcess = createMockAnalysisProcess()
        val step = Step(
            metadata = StepMetadata("No Output Step", UUID.randomUUID(), "Test step"),
            process = analysisProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Workflow execution completed successfully."))
    }

    @Test
    fun testResolveOutputPath_FileDestination() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val retrievalProcess = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.0"
        )

        val step = Step(
            metadata = StepMetadata("Retrieval Step", UUID.randomUUID(), "Test retrieval"),
            outputs = listOf(
                OutputDataSpec(
                    "output",
                    "output",
                    destination = FileDestination("/data/downloads/dataset/file.csv", FileFormat.CSV)
                )
            ),
            process = retrievalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        // This will execute and use the resolved path
        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing DataRetrievalProcess"))
    }

    @Test
    fun testResolveOutputPath_DefaultPath() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val retrievalProcess = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.0"
        )

        val step = Step(
            metadata = StepMetadata("Retrieval Step", UUID.randomUUID(), "Test retrieval"),
            process = retrievalProcess
        )
        // No output specified - should use default path

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing DataRetrievalProcess"))
    }

    @Test
    fun testExtractBasePath_NestedPath() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)

        // We'll test this indirectly through a retrieval process with nested path
        val executorFactory = createMockExecutorFactory()

        val retrievalProcess = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.0"
        )

        val step = Step(
            metadata = StepMetadata("Retrieval Step", UUID.randomUUID(), "Test retrieval"),
            outputs = listOf(
                OutputDataSpec(
                    "output",
                    "output",
                    destination = FileDestination("/a/b/c/file.csv", FileFormat.CSV)
                )
            ),
            process = retrievalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        // Verify execution completed
        val output = outputCapture.toString()
        assertTrue(output.contains("DataRetrievalProcess"))
    }

    @Test
    fun testHandleDataRetrievalProcess_Success() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val retrievalProcess = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val step = Step(
            metadata = StepMetadata("Retrieval Step", UUID.randomUUID(), "Test data retrieval"),
            process = retrievalProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Executing DataRetrievalProcess"))
        assertTrue(output.contains("PhysioNet Data Retrieval"))
    }

    @Test
    fun testDataFlow_ThroughMultipleSteps() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Step 1: Create data
        val inputData = createTestTabularData()
        dataRegistry.register("step1-output", InMemoryData(inputData))

        // Step 2: Process data from step 1
        val step2 = Step(
            metadata = StepMetadata("Step 2", UUID.randomUUID(), "Process data"),
            inputs = listOf(InputDataSpec("input", "input", source = InMemorySource("step1-output"))),
            outputs = listOf(OutputDataSpec("output", "output", destination = RegistryDestination("step2-output"))),
            process = createMockAnalysisProcess()
        )

        // Step 3: Process data from step 2
        val step3 = Step(
            metadata = StepMetadata("Step 3", UUID.randomUUID(), "Final processing"),
            inputs = listOf(InputDataSpec("input", "input", source = InMemorySource("step2-output"))),
            outputs = listOf(OutputDataSpec("output", "output", destination = RegistryDestination("final-output"))),
            process = createMockAnalysisProcess()
        )

        val workflow = Workflow(
            WorkflowMetadata("Data Flow Workflow", "Test data flow", UUID.randomUUID() )
        )
        workflow.addComponent(step2)
        workflow.addComponent(step3)

        strategy.execute(workflow, executorFactory)

        // Verify final output exists
        val finalOutput = dataRegistry.resolve("final-output")
        assertNotNull(finalOutput)

        val output = outputCapture.toString()
        assertTrue(output.contains("Step 2"))
        assertTrue(output.contains("Step 3"))
    }

    @Test
    fun testUnsupportedProcessType() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create an unsupported process type
        val unsupportedProcess = object : dk.cachet.carp.analytics.domain.process.WorkflowProcess {
            override val name: String = "UnsupportedProcess"
            override val description: String = "An unsupported process type"
        }

        val step = Step(
            metadata = StepMetadata("Unsupported Step", UUID.randomUUID(), "Test unsupported"),
            process = unsupportedProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID() )
        )
        workflow.addComponent(step)

        assertFailsWith<IllegalArgumentException> {
            strategy.execute(workflow, executorFactory)
        }
    }

    @Test
    fun testOutputLogging() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        val step = Step(
            metadata = StepMetadata("Logging Test", UUID.randomUUID(), "Test logging"),
            process = createMockAnalysisProcess()
        )

        val workflow = Workflow(
            WorkflowMetadata("Logging Workflow", "Test logging", UUID.randomUUID())
        )
        workflow.addComponent(step)

        strategy.execute(workflow, executorFactory)

        val output = outputCapture.toString()
        assertTrue(output.contains("Starting sequential execution of workflow: Logging Workflow"))
        assertTrue(output.contains("Running step 1/1: Logging Test"))
        assertTrue(output.contains("Workflow execution completed successfully"))
    }

    @Test
    fun testAnalysisProcessExceptionHandling() {
        val strategy = JvmSequentialExecutionStrategy(dataRegistry)
        val executorFactory = createMockExecutorFactory()

        // Create a process that throws an exception
        val failingProcess = object : AnalysisProcess {
            override val name: String = "FailingProcess"
            override val description: String = "A process that fails"

            override fun process(input: ICarpTabularData): ICarpTabularData {
                throw IllegalArgumentException("Test exception")
            }
        }

        val step = Step(
            metadata = StepMetadata("Failing Step", UUID.randomUUID(), "Test failure"),
            process = failingProcess
        )

        val workflow = Workflow(
            WorkflowMetadata("Test Workflow", "A test workflow", UUID.randomUUID())
        )
        workflow.addComponent(step)

        assertFailsWith<IllegalArgumentException> {
            strategy.execute(workflow, executorFactory)
        }

        val output = outputCapture.toString()
        assertTrue(output.contains("Invalid arguments for AnalysisProcess"))
    }
}

