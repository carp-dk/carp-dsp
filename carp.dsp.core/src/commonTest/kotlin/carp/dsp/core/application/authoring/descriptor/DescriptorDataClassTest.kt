
@file:Suppress("REDUNDANT_EXPLICIT_TYPE", "RemoveExplicitTypeArguments")

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
 *
 * The "null-branch coverage" tests compare an instance with a nullable field set against one
 * with it null. This hits both arms of the generated `equals()` null-check branches, which is
 * the main reason the package branch coverage was below 60%.
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
        assertEquals("hello", a.value)
        assertNotEquals<Any>(a, "not-a-descriptor")
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
        assertNotEquals<Any>(a, "not-a-descriptor")
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
        assertNotEquals<Any>(a, "not-a-descriptor")
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
        assertNotEquals<Any>(a, "not-a-descriptor")
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
        assertNotEquals<Any>(a, "not-a-descriptor")
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
        assertNotEquals<Any>(a, "not-a-descriptor")
    }

    // ── TaskDescriptor subtypes ───────────────────────────────────────────────

    @Test
    fun `CommandTaskDescriptor equality hashCode toString copy`()
    {
        val a = CommandTaskDescriptor(
            id = "tid-1", name = "cmd", description = "desc",
            executable = "echo", args = listOf(LiteralArgDescriptor("hi")),
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
    fun `CommandTaskDescriptor equals null-branch coverage`()
    {
        val withId = CommandTaskDescriptor(id = "x", name = "t", executable = "e")
        val withoutId = CommandTaskDescriptor(id = null, name = "t", executable = "e")
        val withDesc = CommandTaskDescriptor(name = "t", executable = "e", description = "d")
        val withoutDesc = CommandTaskDescriptor(name = "t", executable = "e", description = null)

        assertNotEquals(withId, withoutId)
        assertNotEquals(withoutId, withId)
        assertNotEquals(withDesc, withoutDesc)
        assertNotEquals(withoutDesc, withDesc)
        assertNotEquals<Any>(withId, "string")
    }

    @Test
    fun `PythonTaskDescriptor equality hashCode toString copy`()
    {
        val ep = ScriptEntryPointDescriptor("run.py")
        val a = PythonTaskDescriptor(
            id = "tid-2", name = "py", entryPoint = ep,
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
    fun `PythonTaskDescriptor equals null-branch coverage`()
    {
        val ep = ScriptEntryPointDescriptor("run.py")
        val withId = PythonTaskDescriptor(id = "x", name = "t", entryPoint = ep)
        val withoutId = PythonTaskDescriptor(id = null, name = "t", entryPoint = ep)
        val withDesc = PythonTaskDescriptor(name = "t", entryPoint = ep, description = "d")
        val withoutDesc = PythonTaskDescriptor(name = "t", entryPoint = ep, description = null)

        assertNotEquals(withId, withoutId)
        assertNotEquals(withoutId, withId)
        assertNotEquals(withDesc, withoutDesc)
        assertNotEquals(withoutDesc, withDesc)
        assertNotEquals<Any>(withId, "string")
    }

    @Test
    fun `InProcessTaskDescriptor equality hashCode toString copy`()
    {
        val a = InProcessTaskDescriptor(
            id = "tid-3", name = "inproc",
            operationId = "op.run", parameters = mapOf("k" to "v"),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("op.run"))

        val noParams = a.copy(parameters = emptyMap())
        assertNotEquals(a, noParams)
        assertTrue(noParams.parameters.isEmpty())
    }

    @Test
    fun `InProcessTaskDescriptor equals null-branch coverage`()
    {
        val withId = InProcessTaskDescriptor(id = "x", name = "t", operationId = "op")
        val withoutId = InProcessTaskDescriptor(id = null, name = "t", operationId = "op")
        val withDesc = InProcessTaskDescriptor(name = "t", operationId = "op", description = "d")
        val withoutDesc = InProcessTaskDescriptor(name = "t", operationId = "op", description = null)

        assertNotEquals(withId, withoutId)
        assertNotEquals(withoutId, withId)
        assertNotEquals(withDesc, withoutDesc)
        assertNotEquals(withoutDesc, withDesc)
        assertNotEquals<Any>(withId, "string")
    }

    @Test
    fun `EnvironmentDescriptor equality hashCode toString copy`()
    {
        val a = EnvironmentDescriptor(name = "conda-env", kind = "conda")
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("conda-env"))

        assertNotEquals(a, a.copy(kind = "pixi"))
        assertNotEquals(a, a.copy(name = "other"))
    }

    @Test
    fun `EnvironmentDescriptor equals null-branch coverage`()
    {
        val withSpec = EnvironmentDescriptor(name = "e", kind = "conda", spec = mapOf("k" to listOf("v")))
        val withoutSpec = EnvironmentDescriptor(name = "e", kind = "conda")

        assertNotEquals(withSpec, withoutSpec)
        assertNotEquals(withoutSpec, withSpec)
        assertNotEquals<Any>(withSpec, "string")
    }

    // ── StepDescriptor ────────────────────────────────────────────────────────

    @Test
    fun `StepDescriptor equality hashCode toString copy`()
    {
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val a = StepDescriptor(id = "s-1", environmentId = "e-1", task = task, dependsOn = listOf("s-0"))
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("s-1"))

        val noId = a.copy(id = null)
        assertNull(noId.id)
        assertNotEquals(a, noId)
    }

    @Test
    fun `StepDescriptor equals null-branch coverage`()
    {
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val withId = StepDescriptor(id = "s", environmentId = "e", task = task)
        val withoutId = StepDescriptor(id = null, environmentId = "e", task = task)
        val withMeta = StepDescriptor(environmentId = "e", task = task, metadata = StepMetadataDescriptor(name = "m"))
        val withoutMeta = StepDescriptor(environmentId = "e", task = task, metadata = null)

        assertNotEquals(withId, withoutId)
        assertNotEquals(withoutId, withId)
        assertNotEquals(withMeta, withoutMeta)
        assertNotEquals(withoutMeta, withMeta)
        assertNotEquals<Any>(withId, "string")
    }

    // ── WorkflowMetadataDescriptor ────────────────────────────────────────────

    @Test
    fun `WorkflowMetadataDescriptor equality hashCode toString copy`()
    {
        val a = WorkflowMetadataDescriptor(
            id = "wf-1", name = "My Workflow", description = "desc",
            version = "2.1", tags = listOf("prod", "etl"),
        )
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("My Workflow"))
        assertNotEquals(a, a.copy(name = "Other"))

        val defaults = WorkflowMetadataDescriptor(name = "x")
        assertNull(defaults.id)
        assertNull(defaults.description)
        assertEquals("1.0", defaults.version)
        assertTrue(defaults.tags.isEmpty())
    }

    @Test
    fun `WorkflowMetadataDescriptor equals null-branch coverage`()
    {
        assertNotEquals(WorkflowMetadataDescriptor(id = "w", name = "n"), WorkflowMetadataDescriptor(id = null, name = "n"))
        assertNotEquals(WorkflowMetadataDescriptor(id = null, name = "n"), WorkflowMetadataDescriptor(id = "w", name = "n"))
        assertNotEquals(WorkflowMetadataDescriptor(name = "n", description = "d"), WorkflowMetadataDescriptor(name = "n", description = null))
        assertNotEquals(WorkflowMetadataDescriptor(name = "n", description = null), WorkflowMetadataDescriptor(name = "n", description = "d"))
        assertNotEquals<Any>(WorkflowMetadataDescriptor(name = "n"), "string")
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
        assertNotEquals(a, a.copy(schemaVersion = "2.0"))
    }

    @Test
    fun `WorkflowDescriptor copy with steps and environments`()
    {
        val meta = WorkflowMetadataDescriptor(name = "wf")
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val step = StepDescriptor(environmentId = "e1", task = task)
        val env = EnvironmentDescriptor(name = "env", kind = "conda")

        val a = WorkflowDescriptor(
            schemaVersion = "1.0", metadata = meta,
            steps = listOf(step), environments = mapOf("e1" to env),
        )
        assertEquals(1, a.steps.size)
        assertEquals(1, a.environments.size)
        assertNotEquals(a, a.copy(steps = emptyList()))
    }

    @Test
    fun `WorkflowDescriptor equals null-branch coverage`()
    {
        val meta = WorkflowMetadataDescriptor(name = "wf")
        val task = CommandTaskDescriptor(name = "t", executable = "echo")
        val step = StepDescriptor(environmentId = "e", task = task)
        val env = EnvironmentDescriptor(name = "env", kind = "conda")

        val withSteps = WorkflowDescriptor(schemaVersion = "1.0", metadata = meta, steps = listOf(step))
        val withoutSteps = WorkflowDescriptor(schemaVersion = "1.0", metadata = meta)
        val withEnvs = WorkflowDescriptor(schemaVersion = "1.0", metadata = meta, environments = mapOf("e" to env))
        val withoutEnvs = WorkflowDescriptor(schemaVersion = "1.0", metadata = meta)

        assertNotEquals(withSteps, withoutSteps)
        assertNotEquals(withoutSteps, withSteps)
        assertNotEquals(withEnvs, withoutEnvs)
        assertNotEquals(withoutEnvs, withEnvs)
        assertNotEquals<Any>(withSteps, "string")
    }
}
