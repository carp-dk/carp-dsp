package carp.dsp.core.infrastructure.runtime.command

import carp.dsp.core.application.environment.PixiEnvironment
import dk.cachet.carp.analytics.application.runtime.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PixiCommandsTest {

    private val builder = PixiCommands()
    private val env = PixiEnvironment(name = "dev")

    @Test
    fun install_builds_expected_command() {
        val command = builder.install(
            environment = env,
            features = listOf("feature1", "feature2"),
            cwd = "/tmp/project",
            timeoutMs = 2000
        )

        assertEquals("pixi", command.exe)
        assertEquals(listOf("install", "--features", "feature1", "feature2"), command.args)
        assertEquals("/tmp/project", command.cwd)
        assertTrue(command.env.isEmpty())
        assertNull(command.stdin)
        assertEquals(2000, command.timeoutMs)
    }

    @Test
    fun run_builds_expected_command() {
        val envVars = mapOf("FOO" to "BAR")
        val stdinBytes = "input".toByteArray()
        val options = PixiRunOptions(
            cwd = "/tmp/work",
            envVars = envVars,
            stdin = stdinBytes,
            timeoutMs = 1500
        )

        val command: Command = builder.run(
            environment = env,
            exe = "python",
            args = listOf("-m", "pip", "list"),
            options = options
        )

        assertEquals("pixi", command.exe)
        assertEquals(listOf("run", "-e", "dev", "python", "-m", "pip", "list"), command.args)
        assertEquals("/tmp/work", command.cwd)
        assertEquals(envVars, command.env)
        assertEquals(stdinBytes.toList(), command.stdin?.toList())
        assertEquals(1500, command.timeoutMs)
    }
}
