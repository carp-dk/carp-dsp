package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
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
            dependencies = emptyList()
        )

        val command = handler.generateExecutionCommand(ref, "python script.py")

        val expected = "pixi run python script.py"
        kotlin.test.assertEquals(expected, command)
    }

    @Test
    fun `generates execution command with args`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
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
            dependencies = emptyList()
        )

        val result = handler.validate(ref)
        assertFalse(result)
    }

    @Test
    fun `teardown returns true for nonexistent project`() {
        val ref = PixiEnvironmentRef(
            id = "missing-pixi-project",
            dependencies = emptyList()
        )

        val result = handler.teardown(ref)

        assertTrue(result)
    }

    @Test
    fun `setup fails when pixi not installed`() {
        val ref = PixiEnvironmentRef(
            id = "pixi-missing",
            dependencies = emptyList()
        )

        val exception = kotlin.runCatching { handler.setup(ref) }.exceptionOrNull()

        assertTrue(exception is EnvironmentProvisioningException)
    }

    @Test
    fun `validate false when project exists but no python`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = createTempDirectory("pixi-validate-no-python")
        val envId = "no-python-env"
        try {
            System.setProperty("user.home", tempHome.toString())
            projectDir(envId, tempHome).createDirectories()

            val ref = PixiEnvironmentRef(id = envId, dependencies = emptyList())
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

            val ref = PixiEnvironmentRef(id = envId, dependencies = emptyList())
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
