package carp.dsp.core.application.authoring.descriptor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the auto-generated `data class` members (equals, hashCode, toString, copy) and
 * any `init` validations across every descriptor type.
 *
 * These tests exist purely to satisfy Kover line/method coverage for the descriptor package.
 * They also serve as regression guards against accidental field additions or reordering.
 */
class DescriptorDataClassTest
{
    // ── ArgTokenDescriptor subtypes ───────────────────────────────────────────

    @Test
    fun `LiteralArgDescriptor equality hashCode toString copy`()
    {
        val a = LiteralArgDescriptor("hello")
        val b = LiteralArgDescriptor("hello")
        val c = LiteralArgDescriptor("world")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("hello"))

        val copied = a.copy(value = "world")
        assertEquals(c, copied)
        assertEquals("hello", a.value) // original unchanged
    }

    @Test
    fun `InputRefArgDescriptor equality hashCode toString copy`()
    {
        val a = InputRefArgDescriptor("id-1")
        val b = InputRefArgDescriptor("id-1")
        val c = InputRefArgDescriptor("id-2")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("id-1"))

        val copied = a.copy(inputId = "id-2")
        assertEquals(c, copied)
    }

    @Test
    fun `OutputRefArgDescriptor equality hashCode toString copy`()
    {
        val a = OutputRefArgDescriptor("out-1")
        val b = OutputRefArgDescriptor("out-1")
        val c = OutputRefArgDescriptor("out-2")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("out-1"))

        assertEquals(c, a.copy(outputId = "out-2"))
    }

    @Test
    fun `ParamRefArgDescriptor equality hashCode toString copy`()
    {
        val a = ParamRefArgDescriptor("flag")
        val b = ParamRefArgDescriptor("flag")
        val c = ParamRefArgDescriptor("other")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("flag"))

        assertEquals(c, a.copy(name = "other"))
    }

    // ── PythonEntryPointDescriptor subtypes ───────────────────────────────────

    @Test
    fun `ScriptEntryPointDescriptor equality hashCode toString copy`()
    {
        val a = ScriptEntryPointDescriptor("run.py")
        val b = ScriptEntryPointDescriptor("run.py")
        val c = ScriptEntryPointDescriptor("other.py")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("run.py"))

        assertEquals(c, a.copy(scriptPath = "other.py"))
    }

    @Test
    fun `ModuleEntryPointDescriptor equality hashCode toString copy`()
    {
        val a = ModuleEntryPointDescriptor("pkg.cli")
        val b = ModuleEntryPointDescriptor("pkg.cli")
        val c = ModuleEntryPointDescriptor("pkg.other")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("pkg.cli"))

        assertEquals(c, a.copy(moduleName = "pkg.other"))
    }

    // ── TaskDescriptor subtypes ───────────────────────────────────────────────

    @Test
    fun `CommandTaskDescriptor equality hashCode toString copy - all fields`()
    {
        val a = CommandTaskDescriptor(
            id = "tid-1",
            name = "cmd",
            description = "desc",
            executable = "echo",
            args = listOf(LiteralArgDescriptor("hi")),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("echo"))

        val noDesc = a.copy(description = null, id = null)
        assertNull(noDesc.id)
        assertNull(noDesc.description)
        assertNotEquals(a, noDesc)
    }

    @Test
    fun `PythonTaskDescriptor equality hashCode toString copy`()
    {
        val ep = ScriptEntryPointDescriptor("run.py")
        val a = PythonTaskDescriptor(
            id = "tid-2",
            name = "py",
            entryPoint = ep,
            args = listOf(LiteralArgDescriptor("--v")),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("py"))

        val withModule = a.copy(entryPoint = ModuleEntryPointDescriptor("m"))
        assertNotEquals(a, withModule)
    }

    @Test
    fun `InProcessTaskDescriptor equality hashCode toString copy`()
    {
        val a = InProcessTaskDescriptor(
            id = "tid-3",
            name = "inproc",
            operationId = "op.run",
            parameters = mapOf("k" to "v"),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("op.run"))

        val noParams = a.copy(parameters = emptyMap())
        assertNotEquals(a, noParams)
        assertTrue(noParams.parameters.isEmpty())
    }

    // ── StepMetadataDescriptor ────────────────────────────────────────────────

    @Test
    fun `StepMetadataDescriptor equality hashCode toString copy - defaults`()
    {
        val a = StepMetadataDescriptor(name = "step-1", version = "2.0", tags = listOf("etl"))
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("step-1"))

        val renamed = a.copy(name = "step-2")
        assertNotEquals(a, renamed)

        // Default values
        val defaults = StepMetadataDescriptor()
        assertNull(defaults.name)
        assertNull(defaults.description)
        assertEquals("1.0", defaults.version)
        assertTrue(defaults.tags.isEmpty())
    }

    // ── DataPortDescriptor ────────────────────────────────────────────────────

    @Test
    fun `DataPortDescriptor equality hashCode toString copy`()
    {
        val desc = DataDescriptor(type = "csv", format = "UTF-8")
        val a = DataPortDescriptor(id = "p-1", descriptor = desc)
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("p-1"))

        val noId = a.copy(id = null, descriptor = null)
        assertNull(noId.id)
        assertNull(noId.descriptor)
        assertNotEquals(a, noId)
    }

    // ── DataDescriptor ────────────────────────────────────────────────────────

    @Test
    fun `DataDescriptor equality hashCode toString copy - all fields`()
    {
        val a = DataDescriptor(
            type = "json",
            format = "UTF-8",
            schemaRef = "http://schema/v1",
            ontologyRef = "http://onto/concept",
            notes = "raw sensor data",
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("json"))

        val minimal = DataDescriptor()
        assertNull(minimal.type)
        assertNull(minimal.format)
        assertNull(minimal.schemaRef)
        assertNull(minimal.ontologyRef)
        assertNull(minimal.notes)
        assertNotEquals(a, minimal)
    }

    @Test
    fun `DataDescriptor copy preserves only changed fields`()
    {
        val a = DataDescriptor(type = "csv", schemaRef = "s1")
        val b = a.copy(schemaRef = "s2")

        assertEquals("csv", b.type)
        assertEquals("s2", b.schemaRef)
        assertNotEquals(a, b)
    }

    // ── EnvironmentDescriptor ─────────────────────────────────────────────────

    @Test
    fun `EnvironmentDescriptor equality hashCode toString copy`()
    {
        val a = EnvironmentDescriptor(name = "conda-env", kind = "conda")
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("conda-env"))

        val pixi = a.copy(kind = "pixi")
        assertNotEquals(a, pixi)
        assertEquals("pixi", pixi.kind)
        assertEquals("conda-env", pixi.name) // unchanged
    }

    // ── StepDescriptor ────────────────────────────────────────────────────────

    @Test
    fun `StepDescriptor equality hashCode toString copy`()
    {
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val a = StepDescriptor(
            id = "s-1",
            environmentId = "e-1",
            task = task,
            dependsOn = listOf("s-0"),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("s-1"))

        val noId = a.copy(id = null)
        assertNull(noId.id)
        assertNotEquals(a, noId)
    }

    // ── WorkflowMetadataDescriptor ────────────────────────────────────────────

    @Test
    fun `WorkflowMetadataDescriptor equality hashCode toString copy - all fields`()
    {
        val a = WorkflowMetadataDescriptor(
            id = "wf-1",
            name = "My Workflow",
            description = "desc",
            version = "2.1",
            tags = listOf("prod", "etl"),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("My Workflow"))

        val renamed = a.copy(name = "Other")
        assertNotEquals(a, renamed)

        val defaults = WorkflowMetadataDescriptor(name = "x")
        assertNull(defaults.id)
        assertNull(defaults.description)
        assertEquals("1.0", defaults.version)
        assertTrue(defaults.tags.isEmpty())
    }

    // ── WorkflowDescriptor ────────────────────────────────────────────────────

    @Test
    fun `WorkflowDescriptor equality hashCode toString copy`()
    {
        val meta = WorkflowMetadataDescriptor(name = "wf")
        val a = WorkflowDescriptor(schemaVersion = "1.0", metadata = meta)
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("1.0"))

        val v2 = a.copy(schemaVersion = "2.0")
        assertNotEquals(a, v2)
        assertEquals("2.0", v2.schemaVersion)
    }

    @Test
    fun `WorkflowDescriptor copy with steps and environments`()
    {
        val meta = WorkflowMetadataDescriptor(name = "wf")
        val envDesc = EnvironmentDescriptor(name = "env", kind = "conda")
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val step = StepDescriptor(environmentId = "e1", task = task)

        val a = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = meta,
            steps = listOf(step),
            environments = mapOf("e1" to envDesc),
        )

        assertEquals(1, a.steps.size)
        assertEquals(1, a.environments.size)

        val noSteps = a.copy(steps = emptyList())
        assertTrue(noSteps.steps.isEmpty())
        assertNotEquals(a, noSteps)
    }
}

