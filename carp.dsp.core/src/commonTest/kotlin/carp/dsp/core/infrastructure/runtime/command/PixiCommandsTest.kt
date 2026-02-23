package carp.dsp.core.infrastructure.runtime.command

import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixiCommandsTest {

    private val builder = PixiCommands()
    private val env = PixiEnvironmentDefinition(name = "dev", id = UUID.randomUUID())

    @Test
    fun install_builds_expected_commandSpec() {
        val command = builder.install(features = listOf("feature1", "feature2"))

        assertEquals("pixi", command.executable)
        assertEquals(listOf("install", "--features", "feature1", "feature2"), command.args)
    }

    @Test
    fun install_without_features_uses_minimal_args() {
        val command = builder.install()

        assertEquals("pixi", command.executable)
        assertEquals(listOf("install"), command.args)
    }

    @Test
    fun run_builds_expected_commandSpec() {
        val command = builder.run(
            environment = env,
            exe = "python",
            args = listOf("-m", "pip", "list")
        )

        assertEquals("pixi", command.executable)
        assertEquals(listOf("run", "-e", "dev", "python", "-m", "pip", "list"), command.args)
    }

    @Test
    fun run_without_extra_args_invokes_executable_only() {
        val command = builder.run(environment = env, exe = "bash")

        assertEquals(listOf("run", "-e", "dev", "bash"), command.args)
        assertTrue(command.args.none { it.isBlank() })
    }
}
