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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        val sep = FileSystems.getDefault().separator
        val envs = "${System.getProperty("user.home")}$sep.carp-dsp${sep}envs${sep}pixi$sep"

        assertTrue(command.startsWith("pixi run --manifest-path \"$envs"), command)
        assertTrue(command.endsWith("${sep}pixi.toml\" python script.py"), command)
        // Test directory is named for the environment, not for its id.
        assertTrue(command.contains("test-pixi-"), command)
        assertFalse(command.contains("test-001"), command)
    }

    @Test
    fun `the same spec resolves to the same directory, a different one does not`() {
        fun pathFor(id: String, deps: List<String>, python: String = "3.11"): String =
            handler.generateExecutionCommand(
                PixiEnvironmentRef(
                    id = id,
                    name = "shared-pixi",
                    dependencies = deps,
                    pythonVersion = python
                ),
                "python x.py"
            )

        val first = pathFor("run-1", listOf("numpy", "pandas"))

        // Same spec under a different id, and with the dependencies reordered.
        assertEquals(first, pathFor("run-2", listOf("pandas", "numpy")))

        // A changed dependency, and a changed python version, each earn their own.
        assertNotEquals(first, pathFor("run-1", listOf("numpy")))
        assertNotEquals(first, pathFor("run-1", listOf("numpy", "pandas"), python = "3.12"))
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
