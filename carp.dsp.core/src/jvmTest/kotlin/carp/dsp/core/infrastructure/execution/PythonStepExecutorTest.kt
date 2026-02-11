package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.environment.PixiEnvironment
import carp.dsp.core.application.process.PythonProcess
import dk.cachet.carp.analytics.application.runtime.Command
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PythonStepExecutorTest {

    private lateinit var executor: PythonStepExecutor
    private lateinit var scriptPath: Path

    @BeforeTest
    fun setUp() {
        executor = PythonStepExecutor()
        scriptPath = Files.createTempFile("python-step-executor-test", ".py")
    }

    @AfterTest
    fun tearDown() {
        scriptPath.deleteIfExists()
    }

    @Test
    fun execute_direct_python_builds_expected_command() {
        val fakeRunner = QueueRunner(getCommandResult(exitCode = 0))
        overrideRunner(fakeRunner)

        val process = PythonProcess(
            name = "py",
            description = "",
            executionContext = ExecutionContext(),
            scriptPath = scriptPath.toString(),
            arguments = listOf("--opt"),
            pythonExecutable = "python",
            useCondaRun = false
        )
        val step = Step(
            metadata = StepMetadata(name = "s", id = UUID.randomUUID()),
            process = process,
            executionContext = ExecutionContext(envVariables = mapOf("K" to "V"))
        )

        executor.execute(step)

        val cmd = fakeRunner.commands.single()
        assertEquals("python", cmd.exe)
        assertEquals(listOf(scriptPath.toString(), "--opt"), cmd.args)
        assertEquals(mapOf("K" to "V"), cmd.env)
    }

    @Test
    fun execute_conda_python_builds_conda_run_command() {
        val fakeRunner = QueueRunner(getCommandResult(exitCode = 0))
        val envRunner = QueueRunner(
            // env list (missing)
            getCommandResult(exitCode = 0, stdout = ""),
        )
        overrideRunner(fakeRunner)
        overrideEnvRunner(envRunner)

        val process = PythonProcess(
            name = "py",
            description = "",
            executionContext = ExecutionContext(environment = PixiEnvironment("dev")),
            scriptPath = scriptPath.toString(),
            arguments = listOf("--x"),
            pythonExecutable = "python",
            useCondaRun = true
        )
        val step = Step(
            metadata = StepMetadata(name = "s", id = UUID.randomUUID()),
            process = process,
            executionContext = ExecutionContext(environment = PixiEnvironment("dev"), envVariables = mapOf("E" to "1"))
        )

        executor.setup(step)
        executor.execute(step)

        // env setup should have invoked conda env list and create
        assertEquals(2, envRunner.commands.size)
        val cmd = fakeRunner.commands.single()
        assertEquals("conda", cmd.exe)
        assertEquals(listOf("run", "-n", "dev", "python", scriptPath.toString(), "--x"), cmd.args)
        assertEquals(mapOf("E" to "1"), cmd.env)
    }

    @Test
    fun execute_nonzero_exit_throws() {
        val fakeRunner = QueueRunner(getCommandResult(exitCode = 1, stderr = "boom"))
        overrideRunner(fakeRunner)

        val process = PythonProcess(
            name = "py",
            description = "",
            executionContext = ExecutionContext(),
            scriptPath = scriptPath.toString(),
            arguments = emptyList(),
            pythonExecutable = "python",
            useCondaRun = false
        )
        val step = Step(
            metadata = StepMetadata(name = "s", id = UUID.randomUUID()),
            process = process
        )

        assertFailsWith<IllegalStateException> { executor.execute(step) }
    }

    private fun getCommandResult(exitCode: Int, stdout: String = "", stderr: String = "") =
        CommandResult(exitCode = exitCode, stdout = stdout, stderr = stderr, durationMs = 0, timedOut = false)

    private class QueueRunner(private val response: CommandResult) : CommandRunner {
        val commands = mutableListOf<Command>()
        override fun run(command: Command): CommandResult {
            commands += command
            return response
        }
    }

    private fun overrideRunner(fake: QueueRunner) {
        val field = PythonStepExecutor::class.java.getDeclaredField("commandRunner")
        field.isAccessible = true
        field.set(executor, fake)
    }

    private fun overrideEnvRunner(fake: QueueRunner) {
        val field = PythonStepExecutor::class.java.getDeclaredField("environmentSetupExecutor")
        field.isAccessible = true
        val envSetup = field.get(executor) as EnvironmentSetupExecutor

        val runnerField = EnvironmentSetupExecutor::class.java.getDeclaredField("commandRunner")
        runnerField.isAccessible = true
        runnerField.set(envSetup, fake)
    }
}
