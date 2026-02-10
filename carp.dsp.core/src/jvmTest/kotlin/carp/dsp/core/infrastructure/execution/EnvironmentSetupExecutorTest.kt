package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.runtime.Command
import dk.cachet.carp.analytics.application.runtime.CommandResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentSetupExecutorTest {

    private lateinit var executor: EnvironmentSetupExecutor

    @BeforeTest
    fun setUp() {
        executor = EnvironmentSetupExecutor()
    }

    @AfterTest
    fun tearDown() {
        // nothing to clean up
    }

    @Test
    fun condaEnvironmentExists_parses_env_list_output() {
        val runner = queueRunner(
            result(exitCode = 0, stdout = "dev /path/to/dev\n")
        )
        overrideRunner(runner)

        val exists = executor.condaEnvironmentExists("dev")

        assertTrue(exists)
        assertEquals(listOf("conda", "env", "list"), runner.commands.single().let { listOf(it.exe) + it.args })
    }

    @Test
    fun ensureCondaEnvironment_creates_when_missing() {
        val runner = queueRunner(
            // env list (missing env)
            result(exitCode = 0, stdout = "base /path/base\n"),
            // create env success
            result(exitCode = 0)
        )
        overrideRunner(runner)

        val created = executor.ensureCondaEnvironment(
            envName = "dev",
            createIfMissing = true,
            dependencies = listOf("numpy"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )

        assertTrue(created)
        assertEquals(2, runner.commands.size)
        val createCmd = runner.commands[1]
        assertEquals("conda", createCmd.exe)
        assertTrue(createCmd.args.containsAll(listOf("create", "-n", "dev", "python=3.11", "--yes")))
    }

    @Test
    fun ensureCondaEnvironment_returns_false_on_create_failure() {
        val runner = queueRunner(
            // env list (missing env)
            result(exitCode = 0, stdout = ""),
            // create env fails
            result(exitCode = 1, stderr = "boom")
        )
        overrideRunner(runner)

        val created = executor.ensureCondaEnvironment(
            envName = "dev",
            createIfMissing = true,
            dependencies = emptyList(),
            pythonVersion = null,
            channels = emptyList()
        )

        assertFalse(created)
    }

    private fun result(exitCode: Int, stdout: String = "", stderr: String = ""): CommandResult =
        CommandResult(exitCode = exitCode, stdout = stdout, stderr = stderr, durationMs = 0, timedOut = false)

    private class QueueRunner(private val responses: ArrayDeque<CommandResult>) : dk.cachet.carp.analytics.application.runtime.CommandRunner {
        val commands = mutableListOf<Command>()
        override fun run(command: Command): CommandResult {
            commands += command
            return responses.removeFirstOrNull() ?: error("No response configured")
        }
    }

    private fun queueRunner(vararg results: CommandResult) = QueueRunner(ArrayDeque(results.toList()))

    private fun overrideRunner(fake: QueueRunner) {
        val field = EnvironmentSetupExecutor::class.java.getDeclaredField("commandRunner")
        field.isAccessible = true
        field.set(executor, fake)
    }
}

