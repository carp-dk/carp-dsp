package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataReader
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamSequence
import dk.cachet.carp.data.application.SyncPoint
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudyDataStepTest {

    private val studyId = UUID.randomUUID()
    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()

    private fun batch(steps: Int = 1500): DataStreamBatch {
        val b = MutableDataStreamBatch()
        val seq = MutableDataStreamSequence<StepCount>(
            dataStreamId<StepCount>(d1, "phone"), 100L, listOf(1),
            SyncPoint(Instant.fromEpochMilliseconds(1000L), 500L)
        )
        seq.appendMeasurements(measurement(StepCount(steps), 1000L, 2000L))
        b.appendSequence(seq)
        return b
    }

    private fun config(
        targets: List<DeploymentTarget> = listOf(DeploymentTarget(d1)),
        targetsFile: String? = null,
        provenancePath: String? = null,
        columns: List<String>? = null,
    ) = StudyDataStepConfig(
        request = StudyDataRequest(studyId = studyId, targets = targets),
        targetsFile = targetsFile,
        outputPath = "out/measurements.csv",
        provenancePath = provenancePath,
        columns = columns,
    )

    private class Writes {
        val files = mutableMapOf<String, String>()
        fun write(path: String, text: String) { files[path] = text }
    }

    // ── Targets file ──────────────────────────────────────────────────────────

    @Test
    fun `a targets file names deployments, with or without a device role`() {
        val targets = parseTargets(listOf("$d1", "$d2:watch"))

        assertEquals(listOf(DeploymentTarget(d1), DeploymentTarget(d2, "watch")), targets)
    }

    @Test
    fun `blank lines and comments are skipped, so a list can say who a subject is`() {
        val targets = parseTargets(listOf("# cohort A", "", "  $d1:phone  ", "   ", "# done"))

        assertEquals(listOf(DeploymentTarget(d1, "phone")), targets)
    }

    @Test
    fun `a malformed line points at itself`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            parseTargets(listOf("# subjects", "$d1", "not-a-uuid:phone"))
        }

        assertTrue(failure.message!!.contains("Line 3"), failure.message!!)
    }

    // ── Running ───────────────────────────────────────────────────────────────

    @Test
    fun `targets from the file are added to the ones authored inline`() = runTest {
        var seen: StudyDataRequest? = null
        val reader = StudyDataReader({ request, _ ->
            seen = request
        batch()
        })
        val step = StudyDataStep(reader, readFile = { listOf("$d2:watch") }, writeFile = { _, _ -> })

        step.run(config(targets = listOf(DeploymentTarget(d1)), targetsFile = "subjects.txt"))

        assertEquals(listOf(DeploymentTarget(d1), DeploymentTarget(d2, "watch")), seen?.targets)
    }

    @Test
    fun `the measurement table is written, and provenance only when asked for`() = runTest {
        val writes = Writes()
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> batch() }), readFile = { emptyList() }, writeFile = writes::write
        )

        val written = step.run(config())

        assertEquals(listOf("out/measurements.csv"), written)
        assertTrue(writes.files.getValue("out/measurements.csv").startsWith("row_id,"))
    }

    @Test
    fun `both tables are written when a provenance path is configured`() = runTest {
        val writes = Writes()
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> batch() }), readFile = { emptyList() }, writeFile = writes::write
        )

        val written = step.run(config(provenancePath = "out/provenance.csv"))

        assertEquals(listOf("out/measurements.csv", "out/provenance.csv"), written)
        assertTrue(writes.files.getValue("out/provenance.csv").contains("study_deployment_id"))
    }

    @Test
    fun `a configured column list reaches the writer`() = runTest {
        val writes = Writes()
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> batch() }), readFile = { emptyList() }, writeFile = writes::write
        )

        step.run(config(columns = listOf("step_count.steps")))

        val header = writes.files.getValue("out/measurements.csv").lineSequence().first()
        assertTrue(header.endsWith("step_count.steps"), header)
    }

    @Test
    fun `an empty result fails, naming the study and what was asked for`() = runTest {
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> MutableDataStreamBatch() }),
            readFile = { emptyList() }, writeFile = { _, _ -> }
        )

        val failure = assertFailsWith<IllegalStateException> { step.run(config()) }

        // A step that exists to produce data has not succeeded by producing a
        // header row; the failure has to name the request, not just the study.
        assertTrue(failure.message!!.contains(studyId.toString()), failure.message!!)
        assertTrue(failure.message!!.contains("1 target"), failure.message!!)
    }

    @Test
    fun `nothing is written when the result is empty`() = runTest {
        val writes = Writes()
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> MutableDataStreamBatch() }),
            readFile = { emptyList() }, writeFile = writes::write
        )

        assertFailsWith<IllegalStateException> { step.run(config()) }

        assertTrue(writes.files.isEmpty())
    }

    @Test
    fun `an unused data type filter is reported as all in the failure`() = runTest {
        val step = StudyDataStep(
            StudyDataReader({ _, _ -> MutableDataStreamBatch() }),
            readFile = { emptyList() }, writeFile = { _, _ -> }
        )

        val failure = assertFailsWith<IllegalStateException> { step.run(config()) }

        assertTrue(failure.message!!.contains("(all)"), failure.message!!)
    }

    @Test
    fun `DataType is only constructed at the CARP boundary, not in config`() {
        // Guards the convention: the request carries namespaced strings, so a
        // config value never has to be a DataType before it reaches a service.
        val request = StudyDataRequest(studyId = studyId, dataTypes = setOf("dk.cachet.carp.heartrate"))

        assertEquals("dk.cachet.carp.heartrate", request.dataTypes.single())
        assertEquals(DataType.fromString("dk.cachet.carp.heartrate").toString(), request.dataTypes.single())
    }
}
