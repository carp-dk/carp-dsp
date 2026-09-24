package carp.dsp.core.application.authoring.parameters

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeParameterDetectionTest
{
    private fun workflow(vararg steps: StepDescriptor) =
        WorkflowDescriptor(metadata = WorkflowMetadataDescriptor(name = "w"), steps = steps.toList())

    private fun uses(id: String?, vararg args: String) =
        ReferencedStepDescriptor(id = id, uses = "core.io.query-sql", args = args.toList())

    private fun defined(id: String, vararg args: String) = DefinedStepDescriptor(
        id = id,
        environmentId = "env",
        task = CommandTaskDescriptor(name = id, executable = "python", args = args.toList()),
    )

    // ── Detection ─────────────────────────────────────────────────────────────

    @Test
    fun `known keys are found, epoch milliseconds included`()
    {
        val found = detectTimeParameters(workflow(uses("fetch", "--from", "1791799200000", "--to", "2026-10-13")))

        assertEquals(listOf("--from", "--to"), found.map { it.ref.label })
        assertEquals(listOf(TimeFormat.EPOCH_MILLIS, TimeFormat.ISO_DATE), found.map { it.format })
    }

    @Test
    fun `a named parameter is found by its name`()
    {
        val found = detectTimeParameters(
            workflow(uses("query", "--param", "studyId=abc", "--param", "since=2026-10-12T10:00:00Z")),
        )

        assertEquals(
            TimeParameter(ArgumentRef("query", "--param", "since"), "2026-10-12T10:00:00Z", TimeFormat.ISO_DATE_TIME),
            found.single(),
        )
    }

    @Test
    fun `any key holding a date is found by its shape`()
    {
        val found = detectTimeParameters(workflow(defined("clean", "--window-start", "2026-10-12", "--limit", "5")))

        assertEquals("--window-start", found.single().ref.label)
    }

    @Test
    fun `a number under an unknown key is not a time`()
    {
        assertTrue(detectTimeParameters(workflow(defined("clean", "--seed", "1791799200000"))).isEmpty())
    }

    @Test
    fun `a detector added to the chain is used, and earlier detectors win a tie`()
    {
        // Claims every value as a date, so it collides with the known-key detector on --from.
        val greedy = TimeParameterDetector { arguments ->
            arguments.map { (ref, value) -> TimeParameter(ref, value, TimeFormat.ISO_DATE) }
        }

        val found = detectTimeParameters(
            workflow(uses("q", "--at", "noon", "--from", "2026-10-11T00:00:00Z")),
            listOf(KnownKeyDetector(), greedy),
        )

        assertEquals(listOf("--at", "--from"), found.map { it.ref.label })
        assertEquals(listOf(TimeFormat.ISO_DATE, TimeFormat.ISO_DATE_TIME), found.map { it.format })
    }

    @Test
    fun `a step without an id is addressed by its position`()
    {
        val found = detectTimeParameters(workflow(uses("a"), uses(null, "--from", "2026-10-12")))

        assertEquals("#1", found.single().ref.stepKey)
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    @Test
    fun `binding replaces values in place and leaves everything else alone`()
    {
        val original = workflow(
            uses("query", "--param", "studyId=abc", "--param", "since=2026-10-01T00:00:00Z"),
            defined("clean", "--from", "2026-10-01", "--limit", "5"),
        )

        val bound = bindArguments(
            original,
            mapOf(
                ArgumentRef("query", "--param", "since") to "2026-10-12T10:00:00Z",
                ArgumentRef("clean", "--from") to "2026-10-12",
            ),
        )

        assertEquals(
            listOf("--param", "studyId=abc", "--param", "since=2026-10-12T10:00:00Z"),
            (bound.steps[0] as ReferencedStepDescriptor).args,
        )
        assertEquals(
            listOf("--from", "2026-10-12", "--limit", "5"),
            ((bound.steps[1] as DefinedStepDescriptor).task as CommandTaskDescriptor).args,
        )
    }

    @Test
    fun `binding an argument the workflow does not have fails, naming it`()
    {
        val failure = assertFailsWith<IllegalArgumentException> {
            bindArguments(workflow(uses("query", "--limit", "5")), mapOf(ArgumentRef("query", "--from") to "x"))
        }

        assertTrue(failure.message.orEmpty().contains("--from"), failure.message.orEmpty())
    }
}
