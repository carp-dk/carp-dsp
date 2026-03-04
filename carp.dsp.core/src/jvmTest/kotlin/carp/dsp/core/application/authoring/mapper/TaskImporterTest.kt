package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.Module
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Script
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TaskImporterTest
{
    private val namespace = UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    // ── CommandTask: explicit id

    @Test
    fun `importCommandTask preserves explicit UUID id`()
    {
        val taskId = UUID.randomUUID()
        val d = CommandTaskDescriptor(
            id = taskId.toString(), name = "run", executable = "python"
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals(taskId, result.id)
    }

    @Test
    fun `importCommandTask preserves name description and executable`()
    {
        val d = CommandTaskDescriptor(
            id = UUID.randomUUID().toString(),
            name = "validate",
            description = "Validates input",
            executable = "python",
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals("validate", result.name)
        assertEquals("Validates input", result.description)
        assertEquals("python", result.executable)
    }

    @Test
    fun `importCommandTask maps null description to null`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "echo", description = null
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertNull(result.description)
    }

    // ── CommandTask: id generation

    @Test
    fun `importCommandTask generates deterministic id when id is null`()
    {
        val d = CommandTaskDescriptor(id = null, name = "my-task", executable = "echo")

        val first = TaskImporter.importCommandTask( d, namespace )
        val second = TaskImporter.importCommandTask( d, namespace )
        assertEquals(first.id, second.id, "ID should be deterministic for same name + namespace")
    }

    @Test
    fun `importCommandTask generated id differs for different names`()
    {
        val d1 = CommandTaskDescriptor(id = null, name = "task-a", executable = "echo")
        val d2 = CommandTaskDescriptor(id = null, name = "task-b", executable = "echo")

        assertNotEquals(
            TaskImporter.importCommandTask(d1, namespace).id,
            TaskImporter.importCommandTask(d2, namespace).id
        )
    }

    @Test
    fun `importCommandTask generated id differs for different namespaces`()
    {
        val otherNamespace = UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val d = CommandTaskDescriptor(id = null, name = "task", executable = "echo")

        assertNotEquals(
            TaskImporter.importCommandTask(d, namespace).id,
            TaskImporter.importCommandTask(d, otherNamespace).id
        )
    }

    // ── CommandTask: args

    @Test
    fun `importCommandTask maps empty args list`()
    {
        val d = CommandTaskDescriptor(name = "t", executable = "echo", args = emptyList())
        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals(emptyList(), result.args)
    }

    @Test
    fun `importCommandTask maps all arg token variants`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf(
                LiteralArgDescriptor("--flag"),
                InputRefArgDescriptor(inputId.toString()),
                OutputRefArgDescriptor(outputId.toString()),
                ParamRefArgDescriptor("myParam"),
            )
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals(4, result.args.size)
        assertEquals(Literal("--flag"), result.args[0])
        assertEquals(InputRef(inputId), result.args[1])
        assertEquals(OutputRef(outputId), result.args[2])
        assertEquals(ParamRef("myParam"), result.args[3])
    }

    // ── PythonTask: script entrypoint

    @Test
    fun `importPythonTask preserves explicit UUID id`()
    {
        val taskId = UUID.randomUUID()
        val d = PythonTaskDescriptor(
            id = taskId.toString(), name = "run",
            entryPoint = ScriptEntryPointDescriptor("run.py"),
        )

        val result = TaskImporter.importPythonTask( d, namespace )
        assertEquals(taskId, result.id)
    }

    @Test
    fun `importPythonTask maps script entrypoint`()
    {
        val d = PythonTaskDescriptor(
            name = "py-script",
            entryPoint = ScriptEntryPointDescriptor("pipeline/run.py"),
        )

        val result = assertIs<PythonTaskDefinition>(TaskImporter.importTask(d, namespace))
        assertEquals(Script("pipeline/run.py"), result.entryPoint)
    }

    @Test
    fun `importPythonTask maps module entrypoint`()
    {
        val d = PythonTaskDescriptor(
            name = "py-module",
            entryPoint = ModuleEntryPointDescriptor("mypackage.cli"),
        )

        val result = assertIs<PythonTaskDefinition>(TaskImporter.importTask(d, namespace))
        assertEquals(Module("mypackage.cli"), result.entryPoint)
    }

    @Test
    fun `importPythonTask preserves name description and args`()
    {
        val d = PythonTaskDescriptor(
            name = "analyse",
            description = "Runs analysis",
            entryPoint = ScriptEntryPointDescriptor("analyse.py"),
            args = listOf(LiteralArgDescriptor("--verbose")),
        )

        val result = TaskImporter.importPythonTask( d, namespace )
        assertEquals("analyse", result.name)
        assertEquals("Runs analysis", result.description)
        assertEquals(listOf(Literal("--verbose")), result.args)
    }

    // ── PythonTask: id generation

    @Test
    fun `importPythonTask generates deterministic id when id is null`()
    {
        val d = PythonTaskDescriptor(
            id = null, name = "analyse",
            entryPoint = ScriptEntryPointDescriptor("analyse.py"),
        )

        val first = TaskImporter.importPythonTask( d, namespace )
        val second = TaskImporter.importPythonTask( d, namespace )
        assertEquals(first.id, second.id)
    }

    @Test
    fun `importPythonTask generated id is distinct from command task with same name`()
    {
        val cmd = CommandTaskDescriptor(id = null, name = "task", executable = "echo")
        val py = PythonTaskDescriptor(
            id = null, name = "task",
            entryPoint = ScriptEntryPointDescriptor("task.py"),
        )

        // Prefix differs ("task:cmd:" vs "task:py:") so IDs must differ
        assertNotEquals(
            TaskImporter.importCommandTask(cmd, namespace).id,
            TaskImporter.importPythonTask(py, namespace).id,
        )
    }

    // ── importTask dispatch

    @Test
    fun `importTask dispatches CommandTaskDescriptor to CommandTaskDefinition`()
    {
        val d = CommandTaskDescriptor(name = "cmd", executable = "echo")
        assertIs<CommandTaskDefinition>(TaskImporter.importTask(d, namespace))
    }

    @Test
    fun `importTask dispatches PythonTaskDescriptor to PythonTaskDefinition`()
    {
        val d = PythonTaskDescriptor(
            name = "py", entryPoint = ScriptEntryPointDescriptor("x.py")
        )
        assertIs<PythonTaskDefinition>(TaskImporter.importTask(d, namespace))
    }

    @Test
    fun `importTask throws UnsupportedTaskTypeException for InProcessTaskDescriptor`()
    {
        val d = InProcessTaskDescriptor(name = "inproc", operationId = "op-1")
        assertFailsWith<UnsupportedTaskTypeException> {
            TaskImporter.importTask(d, namespace)
        }
    }

    // ── importArgToken variants

    @Test
    fun `importArgToken maps LiteralArgDescriptor`()
    {
        assertEquals( Literal("hello"), TaskImporter.importArgToken( LiteralArgDescriptor("hello"), namespace ) )
    }

    @Test
    fun `importArgToken maps LiteralArgDescriptor with empty string`()
    {
        assertEquals( Literal(""), TaskImporter.importArgToken( LiteralArgDescriptor(""), namespace ) )
    }

    @Test
    fun `importArgToken maps InputRefArgDescriptor with UUID string`()
    {
        val id = UUID.randomUUID()
        assertEquals( InputRef(id), TaskImporter.importArgToken( InputRefArgDescriptor( id.toString() ), namespace ) )
    }

    @Test
    fun `importArgToken maps OutputRefArgDescriptor with UUID string`()
    {
        val id = UUID.randomUUID()
        assertEquals( OutputRef(id), TaskImporter.importArgToken( OutputRefArgDescriptor( id.toString() ), namespace ) )
    }

    @Test
    fun `importArgToken maps ParamRefArgDescriptor`()
    {
        assertEquals( ParamRef("debug"), TaskImporter.importArgToken( ParamRefArgDescriptor("debug"), namespace ) )
    }

    // ── importArgToken: human-readable (non-UUID) port IDs

    @Test
    fun `importArgToken InputRef with human-readable id generates deterministic UUID`()
    {
        val result = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), namespace )
        val inputRef = assertIs<InputRef>( result )
        assertNotNull( inputRef.inputId )
    }

    @Test
    fun `importArgToken InputRef with human-readable id is deterministic`()
    {
        val a = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), namespace )
        val b = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), namespace )
        assertEquals( assertIs<InputRef>(a).inputId, assertIs<InputRef>(b).inputId )
    }

    @Test
    fun `importArgToken InputRef human-readable id differs from UUID string of different name`()
    {
        val a = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), namespace )
        val b = TaskImporter.importArgToken( InputRefArgDescriptor("port-clean-eeg"), namespace )
        assertNotEquals( assertIs<InputRef>(a).inputId, assertIs<InputRef>(b).inputId )
    }

    @Test
    fun `importArgToken InputRef human-readable id differs across namespaces`()
    {
        val other = UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val a = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), namespace )
        val b = TaskImporter.importArgToken( InputRefArgDescriptor("port-raw-eeg"), other )
        assertNotEquals( assertIs<InputRef>(a).inputId, assertIs<InputRef>(b).inputId )
    }

    @Test
    fun `importArgToken OutputRef with human-readable id generates deterministic UUID`()
    {
        val result = TaskImporter.importArgToken( OutputRefArgDescriptor("port-features-csv"), namespace )
        val outputRef = assertIs<OutputRef>( result )
        assertNotNull( outputRef.outputId )
    }

    @Test
    fun `importArgToken OutputRef with human-readable id is deterministic`()
    {
        val a = TaskImporter.importArgToken( OutputRefArgDescriptor("port-features-csv"), namespace )
        val b = TaskImporter.importArgToken( OutputRefArgDescriptor("port-features-csv"), namespace )
        assertEquals( assertIs<OutputRef>(a).outputId, assertIs<OutputRef>(b).outputId )
    }

    @Test
    fun `importArgToken InputRef and OutputRef with same human-readable id produce different UUIDs`()
    {
        // "port:input:x" and "port:output:x" have different prefixes so must produce different UUIDs
        val input =
            assertIs<InputRef>( TaskImporter.importArgToken( InputRefArgDescriptor("shared-port"), namespace ) )
        val output =
            assertIs<OutputRef>( TaskImporter.importArgToken( OutputRefArgDescriptor("shared-port"), namespace ) )
        assertNotEquals( input.inputId, output.outputId )
    }

    // ── Result type correctness

    @Test
    fun `importCommandTask returns CommandTaskDefinition`()
    {
        val d = CommandTaskDescriptor(name = "t", executable = "echo")
        assertIs<CommandTaskDefinition>(TaskImporter.importCommandTask(d, namespace))
    }

    @Test
    fun `importPythonTask returns PythonTaskDefinition`()
    {
        val d = PythonTaskDescriptor(
            name = "p", entryPoint = ModuleEntryPointDescriptor("pkg.cli")
        )
        assertIs<PythonTaskDefinition>(TaskImporter.importPythonTask(d, namespace))
    }

    @Test
    fun `imported task id is never null`()
    {
        val cmd = CommandTaskDescriptor(id = null, name = "c", executable = "echo")
        val py = PythonTaskDescriptor(
            id = null, name = "p",
            entryPoint = ScriptEntryPointDescriptor("p.py")
        )

        assertNotNull(TaskImporter.importCommandTask(cmd, namespace).id)
        assertNotNull(TaskImporter.importPythonTask(py, namespace).id)
    }
}
