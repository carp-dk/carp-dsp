package carp.dsp.core.infrastructure.execution.handlers


import carp.dsp.core.infrastructure.execution.CommandTemplate
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandTemplateTest {

    @Test
    fun `expands conda template`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "my-env",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = listOf("script.py", "arg1", "arg2")
        )

        val command = template.toCommandString()

        assertEquals("conda run -n my-env python script.py arg1 arg2", command)
    }

    @Test
    fun `expands pixi template`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = listOf("script.py", "input.csv", "output.json")
        )

        val command = template.toCommandString()

        assertEquals("pixi run python script.py input.csv output.json", command)
    }

    @Test
    fun `expands system template`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = listOf("script.py")
        )

        val command = template.toCommandString()

        assertEquals("python script.py", command)
    }

    @Test
    fun `handles python with multiple args`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "analysis-env",
            dependencies = listOf("numpy", "pandas")
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = listOf("-m", "mymodule", "arg1", "arg2", "arg3")
        )

        val command = template.toCommandString()

        assertTrue(command.contains("conda run -n analysis-env"))
        assertTrue(command.contains("python -m mymodule arg1 arg2 arg3"))
    }

    @Test
    fun `handles missing args`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = emptyList()
        )

        val command = template.toCommandString()

        assertEquals("conda run -n test-env python", command)
    }

    @Test
    fun `converts to bash command`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "env",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "echo",
            args = listOf("hello", "world")
        )

        val bashCmd = template.toBashCommand()

        assertEquals(3, bashCmd.size)
        assertEquals("bash", bashCmd[0])
        assertEquals("-c", bashCmd[1])
        assertTrue(bashCmd[2].contains("echo hello world"))
    }

    @Test
    fun `preserves special characters in args`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "env",
            dependencies = emptyList()
        )

        val template = CommandTemplate(
            environmentRef = ref,
            executable = "python",
            args = listOf("script.py", "path/to/file.csv", "--option=value")
        )

        val command = template.toCommandString()

        assertTrue(command.contains("path/to/file.csv"))
        assertTrue(command.contains("--option=value"))
    }
}
