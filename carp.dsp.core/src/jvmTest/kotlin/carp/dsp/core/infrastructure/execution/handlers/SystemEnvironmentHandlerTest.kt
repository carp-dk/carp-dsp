package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemEnvironmentHandlerTest {

    private val handler = SystemEnvironmentHandler()

    @Test
    fun `can handle SystemEnvironmentRef`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        assertTrue(handler.canHandle(ref))
    }

    @Test
    fun `cannot handle other environment refs`() {
        val ref = CondaEnvironmentRef(
            id = "conda-001",
            name = "env",
            dependencies = emptyList()
        )

        assertFalse(handler.canHandle(ref))
    }

    @Test
    fun `setup is no-op`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val result = handler.setup(ref)

        assertTrue(result)
    }

    @Test
    fun `generate execution command returns unmodified command`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val command = handler.generateExecutionCommand(ref, "python script.py arg1")

        assertEquals("python script.py arg1", command)
    }

    @Test
    fun `generate execution command preserves complex commands`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val inputCommand = "python -m mymodule --option=value file1.txt file2.txt"
        val command = handler.generateExecutionCommand(ref, inputCommand)

        assertEquals(inputCommand, command)
    }

    @Test
    fun `teardown is no-op`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val result = handler.teardown(ref)

        assertTrue(result)
    }

    @Test
    fun `validate always returns true`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val result = handler.validate(ref)

        assertTrue(result)
    }

    @Test
    fun `all methods are no-ops`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        assertTrue(handler.canHandle(ref))
        assertTrue(handler.setup(ref))
        assertTrue(handler.teardown(ref))
        assertTrue(handler.validate(ref))

        val command = handler.generateExecutionCommand(ref, "test command")
        assertEquals("test command", command)
    }
}
