package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.tasks.*
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
        return ResolvedBindings(
            inputs = mapOf(UUID.randomUUID() to DataRef(UUID.randomUUID(), "text/plain")),
            outputs = mapOf(UUID.randomUUID() to DataRef(UUID.randomUUID(), "application/json"))
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

        assertEquals("Test Step", result.name)
        assertEquals(step.metadata.id, result.stepId)
        assertEquals(step.environmentId, result.environmentDefinitionId)
        assertEquals(bindings, result.bindings)
        assertTrue(result.process is CommandSpec)

        val commandSpec = result.process as CommandSpec
        assertEquals("echo", commandSpec.executable)
        assertEquals(listOf("hello"), commandSpec.args)
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
        assertTrue(errorIssue.message.contains("Only CommandTaskDefinition and PythonTaskDefinition are supported"))
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
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(UUID.randomUUID(), "text/plain")),
            outputs = mapOf(outputId to DataRef(UUID.randomUUID(), "application/json"))
        )
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
        assertEquals(listOf("analysis/run.py", "--verbose"), commandSpec.args)
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
        assertEquals(listOf("-m", "mypackage.cli", "--input", "data.csv"), commandSpec.args)
    }
}
