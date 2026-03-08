package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
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
    fun `importCommandTask maps literal arg`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("--flag")
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals(listOf(Literal("--flag")), result.args)
    }

    @Test
    fun `importCommandTask maps input-ref by index`()
    {
        val inputId = UUID.randomUUID()
        val input = InputDataSpec(id = inputId, name = "port", source = InMemorySource("k"))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("input.0")
        )

        val result = TaskImporter.importCommandTask( d, namespace, inputs = listOf(input) )
        assertEquals(InputRef(inputId), result.args[0])
    }

    @Test
    fun `importCommandTask maps output-ref by index`()
    {
        val outputId = UUID.randomUUID()
        val output = OutputDataSpec(id = outputId, name = "port", destination = RegistryDestination("k"))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("output.0")
        )

        val result = TaskImporter.importCommandTask( d, namespace, outputs = listOf(output) )
        assertEquals(OutputRef(outputId), result.args[0])
    }

    @Test
    fun `importCommandTask maps param-ref`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("param:myParam")
        )

        val result = TaskImporter.importCommandTask( d, namespace )
        assertEquals(ParamRef("myParam"), result.args[0])
    }

    @Test
    fun `importCommandTask maps all arg inference variants`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val input = InputDataSpec(id = inputId, name = "port", source = InMemorySource("k"))
        val output = OutputDataSpec(id = outputId, name = "port", destination = RegistryDestination("k"))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("--flag", "input.0", "output.0", "param:myParam")
        )

        val result = TaskImporter.importCommandTask(
            d, namespace,
            inputs = listOf(input),
            outputs = listOf(output)
        )
        assertEquals(4, result.args.size)
        assertEquals(Literal("--flag"), result.args[0])
        assertEquals(InputRef(inputId), result.args[1])
        assertEquals(OutputRef(outputId), result.args[2])
        assertEquals(ParamRef("myParam"), result.args[3])
    }

    @Test
    fun `importCommandTask input-ref out-of-range generates deterministic UUID`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("input.5")
        )
        val result = TaskImporter.importCommandTask( d, namespace )
        val inputRef = assertIs<InputRef>(result.args[0])
        assertNotNull(inputRef.inputId)
    }

    @Test
    fun `importCommandTask output-ref out-of-range generates deterministic UUID`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("output.5")
        )
        val result = TaskImporter.importCommandTask( d, namespace )
        val outputRef = assertIs<OutputRef>(result.args[0])
        assertNotNull(outputRef.outputId)
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
    fun `importPythonTask preserves name description and maps literal args`()
    {
        val d = PythonTaskDescriptor(
            name = "analyse",
            description = "Runs analysis",
            entryPoint = ScriptEntryPointDescriptor("analyse.py"),
            args = listOf("--verbose"),
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

    // ── Arg inference helpers

    private fun makeInput(id: UUID = UUID.randomUUID()) =
        InputDataSpec(id = id, name = "port", source = InMemorySource("k"))

    private fun makeOutput(id: UUID = UUID.randomUUID()) =
        OutputDataSpec(id = id, name = "port", destination = RegistryDestination("k"))

    private fun infer(arg: String, inputs: List<InputDataSpec> = emptyList(), outputs: List<OutputDataSpec> = emptyList()) =
        TaskImporter.importCommandTask(
            CommandTaskDescriptor(name = "t", executable = "echo", args = listOf(arg)),
            namespace, inputs, outputs
        ).args[0]

    // ── Input references

    @Test
    fun `input-ref index 0 resolves to first input id`()
    {
        val id = UUID.randomUUID()
        assertEquals(InputRef(id), infer("input.0", inputs = listOf(makeInput(id))))
    }

    @Test
    fun `input-ref index 2 resolves to third input id`()
    {
        val ids = List(3) { UUID.randomUUID() }
        val inputs = ids.map { makeInput(it) }
        assertEquals(InputRef(ids[2]), infer("input.2", inputs = inputs))
    }

    @Test
    fun `input-ref out-of-range generates deterministic UUID`()
    {
        val a = assertIs<InputRef>(infer("input.5"))
        val b = assertIs<InputRef>(infer("input.5"))
        assertEquals(a.inputId, b.inputId)
    }

    @Test
    fun `input-ref out-of-range differs from in-range for same index`()
    {
        val id = UUID.randomUUID()
        val inRange = assertIs<InputRef>(infer("input.0", inputs = listOf(makeInput(id))))
        val outRange = assertIs<InputRef>(infer("input.0"))
        assertNotEquals(inRange.inputId, outRange.inputId)
    }

    @Test
    fun `input-ref fallback UUID differs for different index strings`()
    {
        val a = assertIs<InputRef>(infer("input.0"))
        val b = assertIs<InputRef>(infer("input.1"))
        assertNotEquals(a.inputId, b.inputId)
    }

    // ── Output references

    @Test
    fun `output-ref index 0 resolves to first output id`()
    {
        val id = UUID.randomUUID()
        assertEquals(OutputRef(id), infer("output.0", outputs = listOf(makeOutput(id))))
    }

    @Test
    fun `output-ref index 1 resolves to second output id`()
    {
        val ids = List(2) { UUID.randomUUID() }
        val outputs = ids.map { makeOutput(it) }
        assertEquals(OutputRef(ids[1]), infer("output.1", outputs = outputs))
    }

    @Test
    fun `output-ref out-of-range generates deterministic UUID`()
    {
        val a = assertIs<OutputRef>(infer("output.9"))
        val b = assertIs<OutputRef>(infer("output.9"))
        assertEquals(a.outputId, b.outputId)
    }

    @Test
    fun `output-ref fallback UUID differs for different index strings`()
    {
        val a = assertIs<OutputRef>(infer("output.0"))
        val b = assertIs<OutputRef>(infer("output.2"))
        assertNotEquals(a.outputId, b.outputId)
    }

    @Test
    fun `input-ref and output-ref with same index produce different UUIDs`()
    {
        val inputRef = assertIs<InputRef>(infer("input.0"))
        val outputRef = assertIs<OutputRef>(infer("output.0"))
        assertNotEquals(inputRef.inputId, outputRef.outputId)
    }

    // ── Parameter references

    @Test
    fun `param-ref simple name maps to ParamRef`()
    {
        assertEquals(ParamRef("debug"), infer("param:debug"))
    }

    @Test
    fun `param-ref with underscores maps to ParamRef`()
    {
        assertEquals(ParamRef("strict_mode"), infer("param:strict_mode"))
    }

    @Test
    fun `param-ref with alphanumeric name maps to ParamRef`()
    {
        assertEquals(ParamRef("threshold2"), infer("param:threshold2"))
    }

    @Test
    fun `param-ref name is preserved exactly`()
    {
        val result = assertIs<ParamRef>(infer("param:output_format"))
        assertEquals("output_format", result.name)
    }

    @Test
    fun `param-ref is deterministic across calls`()
    {
        assertEquals(infer("param:alpha"), infer("param:alpha"))
    }

    // ── Flags and options (treated as Literals)

    @Test
    fun `double-dash flag maps to Literal`()
    {
        assertEquals(Literal("--verbose"), infer("--verbose"))
    }

    @Test
    fun `double-dash flag with value maps to Literal`()
    {
        assertEquals(Literal("--notch-freq=50"), infer("--notch-freq=50"))
    }

    @Test
    fun `single-dash flag maps to Literal`()
    {
        assertEquals(Literal("-v"), infer("-v"))
    }

    @Test
    fun `double-dash flag with path value maps to Literal`()
    {
        assertEquals(Literal("--config=/etc/app.conf"), infer("--config=/etc/app.conf"))
    }

    @Test
    fun `flag-like string without leading dash maps to Literal`()
    {
        assertEquals(Literal("no-dash-flag"), infer("no-dash-flag"))
    }

    // ── Literals

    @Test
    fun `plain word maps to Literal`()
    {
        assertEquals(Literal("hello"), infer("hello"))
    }

    @Test
    fun `empty string maps to Literal`()
    {
        assertEquals(Literal(""), infer(""))
    }

    @Test
    fun `file path string maps to Literal`()
    {
        assertEquals(Literal("pipeline/run.py"), infer("pipeline/run.py"))
    }

    @Test
    fun `numeric string maps to Literal`()
    {
        assertEquals(Literal("42"), infer("42"))
    }

    @Test
    fun `string resembling param prefix but missing colon maps to Literal`()
    {
        // "param" alone has no colon separator → Literal, not ParamRef
        assertEquals(Literal("param"), infer("param"))
    }

    // ── Error / boundary cases

    @Test
    fun `input-dot-without-digits maps to Literal`()
    {
        // "input." without digits doesn't match the regex → Literal
        assertEquals(Literal("input."), infer("input."))
    }

    @Test
    fun `output-dot-without-digits maps to Literal`()
    {
        assertEquals(Literal("output."), infer("output."))
    }

    @Test
    fun `input-prefix-only maps to Literal`()
    {
        assertEquals(Literal("input"), infer("input"))
    }

    @Test
    fun `param-ref with special characters in name maps to Literal`()
    {
        // "param:my-param" has a hyphen, doesn't match [a-zA-Z0-9_]+ → Literal
        assertEquals(Literal("param:my-param"), infer("param:my-param"))
    }

    @Test
    fun `param-ref with empty name maps to Literal`()
    {
        // "param:" has nothing after the colon → Literal
        assertEquals(Literal("param:"), infer("param:"))
    }

    // ── Integration

    @Test
    fun `all five arg kinds resolved in a single task`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val inputs = listOf(makeInput(inputId))
        val outputs = listOf(makeOutput(outputId))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("script.py", "input.0", "output.0", "param:mode", "--strict")
        )

        val args = TaskImporter.importCommandTask(d, namespace, inputs, outputs).args
        assertEquals(5, args.size)
        assertEquals(Literal("script.py"), args[0])
        assertEquals(InputRef(inputId), args[1])
        assertEquals(OutputRef(outputId), args[2])
        assertEquals(ParamRef("mode"), args[3])
        assertEquals(Literal("--strict"), args[4])
    }

    @Test
    fun `python task applies same arg inference rules`()
    {
        val inputId = UUID.randomUUID()
        val inputs = listOf(makeInput(inputId))
        val d = PythonTaskDescriptor(
            name = "py", entryPoint = ScriptEntryPointDescriptor("run.py"),
            args = listOf("input.0", "--verbose", "param:debug")
        )

        val args = TaskImporter.importPythonTask(d, namespace, inputs = inputs).args
        assertEquals(InputRef(inputId), args[0])
        assertEquals(Literal("--verbose"), args[1])
        assertEquals(ParamRef("debug"), args[2])
    }

    @Test
    fun `arg order is preserved`()
    {
        val d = CommandTaskDescriptor(
            name = "t", executable = "echo",
            args = listOf("c", "a", "b")
        )
        val args = TaskImporter.importCommandTask(d, namespace).args
        assertEquals(listOf(Literal("c"), Literal("a"), Literal("b")), args)
    }

    @Test
    fun `multiple input refs resolve independently by index`()
    {
        val id0 = UUID.randomUUID()
        val id1 = UUID.randomUUID()
        val inputs = listOf(makeInput(id0), makeInput(id1))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("input.1", "input.0")
        )
        val args = TaskImporter.importCommandTask(d, namespace, inputs = inputs).args
        assertEquals(InputRef(id1), args[0])
        assertEquals(InputRef(id0), args[1])
    }

    @Test
    fun `mixed in-range and out-of-range refs in one task`()
    {
        val inId = UUID.randomUUID()
        val outId = UUID.randomUUID()
        val inputs = listOf(makeInput(inId))
        val outputs = listOf(makeOutput(outId))
        val d = CommandTaskDescriptor(
            name = "t", executable = "tool",
            args = listOf("input.0", "input.9", "output.0", "output.9")
        )
        val args = TaskImporter.importCommandTask(d, namespace, inputs, outputs).args
        assertEquals(InputRef(inId), args[0]) // in-range → actual id
        assertIs<InputRef>(args[1]) // out-of-range → fallback UUID
        assertNotEquals(inId, assertIs<InputRef>(args[1]).inputId)
        assertEquals(OutputRef(outId), args[2]) // in-range → actual id
        assertIs<OutputRef>(args[3]) // out-of-range → fallback UUID
        assertNotEquals(outId, assertIs<OutputRef>(args[3]).outputId)
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
