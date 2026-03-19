package carp.dsp.core.infrastructure.execution.handlers

import carp.dsp.core.testing.MockCommandRunner
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixiEnvironmentHandlerTest {

    private val handler = PixiEnvironmentHandler()

    @Test
    fun `can handle PixiEnvironmentRef`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
            name = "test-pixi",
            dependencies = emptyList()
        )

        assertTrue(handler.canHandle(ref))
    }

    @Test
    fun `cannot handle other environment refs`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        assertFalse(handler.canHandle(ref))
    }

    @Test
    fun `generates execution command`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
            name = "test-pixi",
            dependencies = emptyList()
        )

        val command = handler.generateExecutionCommand(ref, "python script.py")

        val expected = "pixi run --manifest-path \"${System.getProperty("user.home")}${
            FileSystems.getDefault().separator
        }.carp-dsp${FileSystems.getDefault().separator}envs${FileSystems.getDefault().separator}pixi${
            FileSystems.getDefault().separator
        }test-001${
            FileSystems.getDefault().separator
        }pixi.toml\" python script.py"
        kotlin.test.assertEquals(expected, command)
    }

    @Test
    fun `generates execution command with args`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
            name = "test-pixi-args",
            dependencies = listOf("numpy", "pandas")
        )

        val command = handler.generateExecutionCommand(
            ref,
            "python analysis.py input.csv output.json"
        )

        assertTrue(command.startsWith("pixi run"))
        assertTrue(command.contains("analysis.py"))
    }

    @Test
    fun `validate returns false for nonexistent project`() {
        val ref = PixiEnvironmentRef(
            id = "definitely-does-not-exist",
            name = "nonexistent-pixi",
            dependencies = emptyList()
        )

        val result = handler.validate(ref)
        assertFalse(result)
    }

    @Test
    fun `teardown returns true for nonexistent project`() {
        val ref = PixiEnvironmentRef(
            id = "missing-pixi-project",
            name = "missing-pixi",
            dependencies = emptyList()
        )

        val result = handler.teardown(ref)

        assertTrue(result)
    }

    @Test
    fun `setup fails when pixi not installed`() {
        val mock = MockCommandRunner().apply {
            on("pixi --version", exitCode = 1) // simulate pixi not found
        }
        val handlerWithMock = PixiEnvironmentHandler(runner = mock)
        val ref = PixiEnvironmentRef(
            id = "pixi-missing",
            name = "missing-pixi-setup",
            dependencies = emptyList()
        )

        val exception = kotlin.runCatching { handlerWithMock.setup(ref) }.exceptionOrNull()

        assertTrue(exception is EnvironmentSetupException)
    }

    @Test
    fun `validate false when project exists but no python`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = createTempDirectory("pixi-validate-no-python")
        val envId = "no-python-env"
        try {
            System.setProperty("user.home", tempHome.toString())
            projectDir(envId, tempHome).createDirectories()

            val ref = PixiEnvironmentRef(
                id = envId,
                name = "no-python-pixi",
                dependencies = emptyList()
            )
            val result = handler.validate(ref)

            assertFalse(result)
        } finally {
            System.setProperty("user.home", originalHome)
            tempHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate false when python executable is invalid`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = createTempDirectory("pixi-validate-bad-python")
        val envId = "bad-python-env"
        try {
            System.setProperty("user.home", tempHome.toString())
            val projectDir = projectDir(envId, tempHome)
            val pythonPath = projectDir.resolve(".pixi/envs/default/bin/python")
            pythonPath.parent.createDirectories()
            pythonPath.writeText("echo not a real python")

            val ref = PixiEnvironmentRef(
                id = envId,
                name = "bad-python-pixi",
                dependencies = emptyList()
            )
            val result = handler.validate(ref)

            assertFalse(result)
        } finally {
            System.setProperty("user.home", originalHome)
            tempHome.toFile().deleteRecursively()
        }
    }

    private fun projectDir(envId: String, home: Path): Path =
        home.resolve(".carp-dsp/envs/pixi/$envId")
}
