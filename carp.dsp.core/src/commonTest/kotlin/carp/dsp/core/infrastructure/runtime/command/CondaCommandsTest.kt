package carp.dsp.core.infrastructure.runtime.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CondaCommandsTest {

    private val builder = CondaCommands()

    @Test
    fun envList_builds_expected_commandSpec() {
        val command = builder.envList()

        assertEquals("conda", command.executable)
        assertEquals(listOf("env", "list"), command.args)
    }

    @Test
    fun envList_with_timeout_builds_expected_commandSpec() {
        val command = builder.envList()
        assertEquals("conda", command.executable)
        assertEquals(listOf("env", "list"), command.args)
    }

    @Test
    fun createEnv_builds_expected_args() {
        val command = builder.createEnv(
            name = "test-env",
            pythonVersion = "3.11",
            channels = listOf("conda-forge"),
            packages = listOf("pandas", "numpy")
        )

        assertEquals("conda", command.executable)
        assertEquals(
            listOf("create", "-n", "test-env", "-c", "conda-forge", "python=3.11", "pandas", "numpy", "--yes"),
            command.args
        )
    }

    @Test
    fun createEnv_with_defaults_builds_expected_args() {
        val command = builder.createEnv(name = "default-env")
        assertEquals("conda", command.executable)
        assertEquals(
            listOf("create", "-n", "default-env", "python", "--yes"),
            command.args
        )
    }

    @Test
    fun createEnv_empty_channels_and_packages() {
        val command = builder.createEnv(name = "empty-env", channels = emptyList(), packages = emptyList())
        assertEquals("conda", command.executable)
        assertEquals(listOf("create", "-n", "empty-env", "python", "--yes"), command.args)
    }

    @Test
    fun installPackages_builds_expected_args() {
        val command = builder.installPackages(
            envName = "test-env",
            packages = listOf("scipy"),
            channels = listOf("conda-forge")
        )

        assertEquals("conda", command.executable)
        assertEquals(
            listOf("run", "-n", "test-env", "conda", "install", "-c", "conda-forge", "scipy", "-y"),
            command.args
        )
    }

    @Test
    fun installPackages_no_channels_no_packages() {
        val command = builder.installPackages(envName = "empty", packages = emptyList(), channels = emptyList())
        assertEquals("conda", command.executable)
        assertEquals(listOf("run", "-n", "empty", "conda", "install", "-y"), command.args)
    }

    @Test
    fun runInEnv_builds_expected_commandSpec() {
        val command = builder.runInEnv(
            envName = "test-env",
            exe = "python",
            args = listOf("-m", "pip", "list")
        )

        assertEquals("conda", command.executable)
        assertEquals(listOf("run", "-n", "test-env", "python", "-m", "pip", "list"), command.args)
    }

    @Test
    fun runInEnv_with_no_args() {
        val command = builder.runInEnv(envName = "test-env", exe = "bash")
        assertEquals("conda", command.executable)
        assertEquals(listOf("run", "-n", "test-env", "bash"), command.args)
        assertTrue(command.args.none { it.isBlank() })
    }
}
