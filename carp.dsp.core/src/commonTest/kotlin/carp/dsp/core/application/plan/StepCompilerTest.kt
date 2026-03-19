package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for StepCompiler.
 *
 * Tests cover compilation to PlannedStep, ResolvedBindings, PlanIssue collection,
 * and support for different TaskDefinition types.
 */
class StepCompilerTest {

    private val compiler = StepCompiler()

    // Mock task definition for testing
    private class MockTaskDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    private fun createTestStep(
        stepName: String,
        taskName: String,
        taskDescription: String? = null,
        task: TaskDefinition? = null,
        version: Version = Version(1, 0)
    ): Step {
        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = stepName,
                description = "Test step: $stepName",
                version = version
            ),
            task = task ?: MockTaskDefinition(
                name = taskName,
                description = taskDescription
            ),
            environmentId = UUID.randomUUID(),
            inputs = emptyList(),
            outputs = emptyList()
        )
    }

    // Helper methods for creating test data
    private fun createEmptyBindings(): ResolvedBindings {
        return ResolvedBindings(emptyMap(), emptyMap())
    }

    private fun createBindingsWithInputsOutputs(): ResolvedBindings {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val inputDataSource = FileLocation(
            path = "/data/input.csv",
            format = FileFormat.CSV,
        )
        val outputDataSource = FileLocation(
            path = "/data/output.json",
            format = FileFormat.JSON,
            metadata = mapOf("overwrite" to "true"),
        )

        val inputSpec = InputDataSpec(
            id = inputId,
            name = "input-data",
            description = "Test input data",
            location = inputDataSource,
        )
        val outputSpec = OutputDataSpec(
            id = outputId,
            name = "output-data",
            description = "Test output data",
            location = outputDataSource,
        )

        val resolvedInputDataSource = FileLocation(
            path = "/workspace/inputs/$inputId.txt",
        )

        val resolvedOutputDataSource = FileLocation(
            path = "/workspace/outputs/$outputId.txt"
        )

        val resolvedInput = ResolvedInput(inputSpec, resolvedInputDataSource)
        val resolvedOutput = ResolvedOutput(outputSpec, resolvedOutputDataSource)

        return ResolvedBindings(
            inputs = mapOf(UUID.randomUUID() to resolvedInput),
            outputs = mapOf(UUID.randomUUID() to resolvedOutput)
        )
    }

    @Test
    fun `compile produces successful result for CommandTaskDefinition`() {
        // Arrange
        val commandTask = CommandTaskDefinition(
            id = UUID.randomUUID(),
            name = "test-command",
            executable = "echo",
            args = listOf(Literal("hello"))
        )
        val step = createTestStep("Test Step", "test-task", task = commandTask)
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(issues.isEmpty())

        assertEquals("Test Step", result.metadata.name)
        assertEquals(step.metadata.id, result.metadata.id)
        assertEquals(step.environmentId, result.environmentRef)
        assertEquals(bindings, result.bindings)
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("echo", commandSpec.executable)
        assertEquals(listOf(ExpandedArg.Literal("hello")), commandSpec.args)
    }

    @Test
    fun `compile returns null and emits error for non-CommandTaskDefinition`() {
        // Arrange
        val step = createTestStep("Test Step", "analysis-task") // Uses MockTaskDefinition
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertEquals(null, result)
        assertTrue(issues.any { it.severity == PlanIssueSeverity.ERROR })

        val errorIssue = issues.find { it.code == "UNSUPPORTED_TASK_TYPE" }
        assertNotNull(errorIssue)
        assertEquals(PlanIssueSeverity.ERROR, errorIssue.severity)
        assertTrue(errorIssue.message.contains("MockTaskDefinition"))
        assertTrue(errorIssue.message.contains("Only CommandTaskDefinition, PythonTaskDefinition, and RTaskDefinition are supported"))
        assertEquals(step.metadata.id, errorIssue.stepId)
    }

    @Test
    fun `compile returns null and emits error for blank step name`() {
        // Arrange
        val step = Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "",
                description = "Test step"
            ),
            task = MockTaskDefinition(name = "valid-task"),
            environmentId = UUID.randomUUID(),
            inputs = emptyList(),
            outputs = emptyList()
        )
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertEquals(null, result)
        assertTrue(issues.any { it.severity == PlanIssueSeverity.ERROR })

        val errorIssue = issues.find { it.code == "STEP_NAME_BLANK" }
        assertNotNull(errorIssue)
        assertEquals(PlanIssueSeverity.ERROR, errorIssue.severity)
        assertEquals(step.metadata.id, errorIssue.stepId)
    }

    @Test
    fun `compile handles CommandTaskDefinition`() {
        // Arrange
        val commandTask = CommandTaskDefinition(
            id = UUID.randomUUID(),
            name = "copy-command",
            executable = "cp",
            args = listOf(
                Literal("--verbose"),
                InputRef(UUID.randomUUID()),
                OutputRef(UUID.randomUUID())
            )
        )
        val step = createTestStep("Command Step", "copy-task", task = commandTask)
        val bindings = createBindingsWithInputsOutputs()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("cp", commandSpec.executable)
        assertTrue(commandSpec.args.isNotEmpty())
    }

    @Test
    fun `compile uses provided ResolvedBindings`() {
        // Arrange
        val commandTask = CommandTaskDefinition(
            id = UUID.randomUUID(),
            name = "bindings-test-command",
            executable = "cp",
            args = listOf(Literal("--test"))
        )
        val step = createTestStep("Bindings Test", "test-task", task = commandTask)
        val bindings = createBindingsWithInputsOutputs()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertEquals(bindings, result.bindings)
        assertEquals(1, result.bindings.inputs.size)
        assertEquals(1, result.bindings.outputs.size)
        assertTrue(result.process is CommandSpec)
    }

    @Test
    fun `compile produces CommandSpec for PythonTaskDefinition with Script entry point`() {
        // Arrange
        val pythonTask = PythonTaskDefinition(
            id = UUID.randomUUID(),
            name = "python-script-task",
            entryPoint = Script("analysis/run.py"),
            args = listOf(Literal("--verbose"))
        )
        val step = createTestStep("Python Script Step", "python-task", task = pythonTask)
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(issues.isEmpty())
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("python", commandSpec.executable)
        assertEquals(listOf(ExpandedArg.Literal("analysis/run.py"), ExpandedArg.Literal("--verbose")), commandSpec.args)
    }

    @Test
    fun `compile produces CommandSpec for PythonTaskDefinition with Module entry point`() {
        // Arrange
        val pythonTask = PythonTaskDefinition(
            id = UUID.randomUUID(),
            name = "python-module-task",
            entryPoint = Module("mypackage.cli"),
            args = listOf(Literal("--input"), Literal("data.csv"))
        )
        val step = createTestStep("Python Module Step", "python-task", task = pythonTask)
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(issues.isEmpty())
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("python", commandSpec.executable)
        assertEquals(
            listOf(
            ExpandedArg.Literal("-m"),
            ExpandedArg.Literal("mypackage.cli"),
            ExpandedArg.Literal("--input"),
            ExpandedArg.Literal("data.csv")
            ),
            commandSpec.args
        )
    }

    @Test
    fun `compile produces CommandSpec for RTaskDefinition with RScript entry point`() {
        // Arrange
        val rTask = RTaskDefinition(
            id = UUID.randomUUID(),
            name = "r-script-task",
            entryPoint = RScript("analysis/run.R"),
            args = listOf(Literal("--verbose"))
        )
        val step = createTestStep("R Script Step", "r-task", task = rTask)
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(issues.isEmpty())
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("Rscript", commandSpec.executable)
        assertEquals(listOf(ExpandedArg.Literal("analysis/run.R"), ExpandedArg.Literal("--verbose")), commandSpec.args)
    }

    @Test
    fun `compile produces CommandSpec for RTaskDefinition with multiple args and bindings`() {
        // Arrange
        val inputRefId = UUID.randomUUID()
        val outputRefId = UUID.randomUUID()
        val rTask = RTaskDefinition(
            id = UUID.randomUUID(),
            name = "r-analysis-task",
            entryPoint = RScript("scripts/analysis.R"),
            args = listOf(
                InputRef(inputRefId),
                OutputRef(outputRefId),
                Literal("--mode=analysis")
            )
        )
        val step = createTestStep("R Analysis Step", "r-task", task = rTask)
        val bindings = createBindingsWithInputsOutputs()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("Rscript", commandSpec.executable)
        // First arg is always the script path
        assertEquals(ExpandedArg.Literal("scripts/analysis.R"), commandSpec.args[0])
        // Should have at least script path + literal arg
        assertTrue(commandSpec.args.size >= 2)
    }

    @Test
    fun `compile handles RTaskDefinition with description`() {
        // Arrange
        val rTask = RTaskDefinition(
            id = UUID.randomUUID(),
            name = "r-task-with-desc",
            description = "R analysis task with description",
            entryPoint = RScript("process.R"),
            args = emptyList()
        )
        val step = createTestStep("R Process Step", "r-task", task = rTask)
        val bindings = createEmptyBindings()
        val issues = mutableListOf<PlanIssue>()

        // Act
        val result = compiler.compile(step, bindings, issues)

        // Assert
        assertNotNull(result)
        assertTrue(issues.isEmpty())
        assertEquals("R Process Step", result.metadata.name)
    }
}
