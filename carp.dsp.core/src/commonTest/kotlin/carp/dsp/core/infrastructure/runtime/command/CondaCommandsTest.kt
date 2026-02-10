package carp.dsp.core.infrastructure.runtime.command

import dk.cachet.carp.analytics.application.runtime.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CondaCommandsTest {

    private val builder = CondaCommands()

    @Test
    fun envList_builds_expected_command() {
        val command = builder.envList(timeoutMs = 1234)

        assertEquals("conda", command.exe)
        assertEquals(listOf("env", "list"), command.args)
        assertNull(command.cwd)
        assertTrue(command.env.isEmpty())
        assertNull(command.stdin)
        assertEquals(1234, command.timeoutMs)
    }

    @Test
    fun createEnv_builds_expected_args() {
        val command = builder.createEnv(
            name = "test-env",
            pythonVersion = "3.11",
            channels = listOf("conda-forge"),
            packages = listOf("pandas", "numpy"),
            timeoutMs = 5000
        )

        assertEquals("conda", command.exe)
        assertEquals(
            listOf("create", "-n", "test-env", "-c", "conda-forge", "python=3.11", "pandas", "numpy", "--yes"),
            command.args
        )
        assertEquals(5000, command.timeoutMs)
        assertTrue(command.env.isEmpty())
        assertNull(command.stdin)
    }

    @Test
    fun installPackages_builds_expected_args() {
        val command = builder.installPackages(
            envName = "test-env",
            packages = listOf("scipy"),
            channels = listOf("conda-forge"),
            timeoutMs = 2500
        )

        assertEquals("conda", command.exe)
        assertEquals(
            listOf("run", "-n", "test-env", "conda", "install", "-c", "conda-forge", "scipy", "-y"),
            command.args
        )
        assertEquals(2500, command.timeoutMs)
    }

    @Test
    fun runInEnv_builds_expected_command() {
        val stdinBytes = "input".toByteArray()
        val envVars = mapOf("FOO" to "BAR")

        val command: Command = builder.runInEnv(
            envName = "test-env",
            exe = "python",
            args = listOf("-m", "pip", "list"),
            options = CondaRunOptions(
                cwd = "/tmp/work",
                envVars = envVars,
                stdin = stdinBytes,
                timeoutMs = 1500
            )
        )

        assertEquals("conda", command.exe)
        assertEquals(listOf("run", "-n", "test-env", "python", "-m", "pip", "list"), command.args)
        assertEquals("/tmp/work", command.cwd)
        assertEquals(envVars, command.env)
        assertEquals(stdinBytes.toList(), command.stdin?.toList())
        assertEquals(1500, command.timeoutMs)
    }
}
