package carp.dsp.core.infrastructure.execution

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskRuntimeTest {

    private val original = TaskRuntime.directory

    @AfterTest
    fun restore() {
        TaskRuntime.directory = original
    }

    @Test
    fun `the token becomes the runtime jar's path`() {
        TaskRuntime.directory = Path.of("/opt/carp/task-runtime")

        assertEquals(
            TaskRuntime.substitute(TaskRuntime.TOKEN),
            Path.of("/opt/carp/task-runtime/carp-task-runtime.jar").toString(),
        )
    }

    @Test
    fun `an argument without the token is returned as it was`() {
        val argument = "--study-id"

        assertEquals(argument, TaskRuntime.substitute(argument))
    }

    @Test
    fun `the token is replaced wherever it appears in an argument`() {
        TaskRuntime.directory = Path.of("/opt/carp/task-runtime")

        val substituted = TaskRuntime.substitute("-Dagent=${TaskRuntime.TOKEN}")

        assertTrue(substituted.startsWith("-Dagent="), substituted)
        assertTrue(substituted.endsWith("carp-task-runtime.jar"), substituted)
    }

    @Test
    fun `the directory is read per call, not captured`() {
        TaskRuntime.directory = Path.of("/first")
        val first = TaskRuntime.substitute(TaskRuntime.TOKEN)

        TaskRuntime.directory = Path.of("/second")
        val second = TaskRuntime.substitute(TaskRuntime.TOKEN)

        assertTrue(first.contains("first"), first)
        assertTrue(second.contains("second"), second)
    }

    @Test
    fun `the runtime defaults beside the provisioned environments`() {
        assertTrue(original.toString().contains(".carp-dsp"), original.toString())
        assertTrue(original.endsWith("task-runtime"), original.toString())
    }
}
