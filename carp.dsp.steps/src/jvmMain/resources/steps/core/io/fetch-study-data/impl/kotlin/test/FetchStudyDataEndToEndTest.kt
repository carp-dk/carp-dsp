package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataReader
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.Acceleration
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamsConfiguration
import dk.cachet.carp.data.application.DataStreamsConfiguration.ExpectedDataStream
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamSequence
import dk.cachet.carp.data.application.SyncPoint
import dk.cachet.carp.data.infrastructure.InMemoryDataStreamService
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class FetchStudyDataEndToEndTest {

    private val studyId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(0L), 0L)

    private val stepCount = dataStreamId<StepCount>(alice, "phone")
    private val heartRate = dataStreamId<HeartRate>(alice, "watch")
    private val bobSteps = dataStreamId<StepCount>(bob, "phone")
    private val acceleration = dataStreamId<Acceleration>(bob, "phone")

    private suspend fun seededService(): InMemoryDataStreamService {
        val service = InMemoryDataStreamService()

        service.openDataStreams(
            DataStreamsConfiguration(
                alice,
                setOf(
                    ExpectedDataStream.fromDataStreamId(stepCount),
                    ExpectedDataStream.fromDataStreamId(heartRate),
                )
            )
        )
        service.openDataStreams(
            DataStreamsConfiguration(
                bob,
                setOf(
                    ExpectedDataStream.fromDataStreamId(bobSteps),
                    ExpectedDataStream.fromDataStreamId(acceleration),
                )
            )
        )

        val aliceBatch = MutableDataStreamBatch()
        aliceBatch.appendSequence(
            MutableDataStreamSequence<StepCount>(stepCount, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(
                    measurement(StepCount(1000), 1_000_000L),
                    measurement(StepCount(2000), 2_000_000L),
                )
            }
        )
        aliceBatch.appendSequence(
            MutableDataStreamSequence<HeartRate>(heartRate, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(measurement(HeartRate(60), 1_500_000L))
            }
        )
        service.appendToDataStreams(alice, aliceBatch)

        val bobBatch = MutableDataStreamBatch()
        bobBatch.appendSequence(
            MutableDataStreamSequence<StepCount>(bobSteps, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(measurement(StepCount(500), 1_000_000L))
            }
        )
        bobBatch.appendSequence(
            MutableDataStreamSequence<Acceleration>(acceleration, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(measurement(Acceleration(1.0, 2.0, 3.0), 1_200_000L))
            }
        )
        service.appendToDataStreams(bob, bobBatch)

        return service
    }

    private class Writes {
        val files = mutableMapOf<String, String>()
        fun write(path: String, text: String) { files[path] = text }
    }

    private fun stepOver(service: InMemoryDataStreamService, writes: Writes) = StudyDataStep(
        reader = StudyDataReader(CarpStudyDataSource(service)),
        readFile = { emptyList() },
        writeFile = writes::write,
    )

    private fun config(
        targets: List<DeploymentTarget>,
        dataTypes: Set<String> = emptySet(),
        fromMs: Long? = null,
        toMs: Long? = null,
        provenance: String? = null,
    ) = StudyDataStepConfig(
        request = StudyDataRequest(studyId, targets, dataTypes, fromMs, toMs),
        outputPath = "measurements.csv",
        provenancePath = provenance,
    )

    private fun rows(csv: String) = csv.trimEnd('\n').split("\n").drop(1)

    /** The (deployment, device role) pairs a provenance table actually covers. */
    private fun deviceOf(provenance: String): Set<Pair<UUID, String>> =
        rows(provenance)
            .map { it.split(",") }
            .map { UUID.parse(it[1]) to it[2] }
            .toSet()

    // ── The whole chain ───────────────────────────────────────────────────────

    @Test
    fun `a study's data becomes a measurement table and a provenance table`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        val written = step.run(
            config(listOf(DeploymentTarget(alice), DeploymentTarget(bob)), provenance = "provenance.csv")
        )

        assertEquals(listOf("measurements.csv", "provenance.csv"), written)

        val measurements = writes.files.getValue("measurements.csv")
        assertEquals(5, rows(measurements).size, measurements)

        // Columns are the union of the types present, one per value they carry.
        // The prefix is the data type's own name, which CARP spells as one word:
        // `dk.cachet.carp.heartrate`, not `heart_rate`.
        val header = measurements.lineSequence().first()
        assertTrue(header.contains("acceleration.x"), header)
        assertTrue(header.contains("heartrate.bpm"), header)
        assertTrue(header.contains("stepcount.steps"), header)

        // Every measurement row joins to exactly one provenance row.
        val ids = rows(measurements).map { it.substringBefore(",") }
        assertEquals(ids, rows(writes.files.getValue("provenance.csv")).map { it.substringBefore(",") })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `provenance carries the deployment and device each row came from`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        step.run(config(listOf(DeploymentTarget(alice)), provenance = "provenance.csv"))

        val provenance = writes.files.getValue("provenance.csv")
        assertTrue(provenance.contains("$alice"), "no deployment id in provenance")
        assertTrue(provenance.contains(",phone,"), "no phone rows")
        assertTrue(provenance.contains(",watch,"), "no watch rows")
        assertTrue(!provenance.contains("$bob"), "bob's data leaked into Alice's table")
    }

    // ── The filters that reach CARP ───────────────────────────────────────────

    @Test
    fun `a device role narrows a deployment to one subject's device`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        step.run(config(listOf(DeploymentTarget(alice, "watch"))))

        val measurements = writes.files.getValue("measurements.csv")
        assertEquals(1, rows(measurements).size, measurements)
        assertTrue(measurements.contains("heartrate"), measurements)
        assertTrue(!measurements.contains("stepcount"), measurements)
    }

    @Test
    fun `pairs are not crossed when two subjects ask for different devices`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        step.run(
            config(
                listOf(DeploymentTarget(alice, "watch"), DeploymentTarget(bob, "phone")),
                provenance = "provenance.csv",
            )
        )

        assertEquals(
            3,
            rows(writes.files.getValue("measurements.csv")).size,
            writes.files.getValue("measurements.csv"),
        )
        assertEquals(
            setOf(alice to "watch", bob to "phone"),
            deviceOf(writes.files.getValue("provenance.csv")),
        )
    }

    @Test
    fun `a data type filter reaches the service`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        step.run(
            config(
                listOf(DeploymentTarget(alice), DeploymentTarget(bob)),
                dataTypes = setOf("dk.cachet.carp.stepcount"),
            )
        )

        val measurements = writes.files.getValue("measurements.csv")
        assertEquals(3, rows(measurements).size, measurements)
        assertTrue(!measurements.contains("heartrate"), measurements)
    }

    @Test
    fun `the time window is half-open, so a row on the end time is excluded`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        step.run(config(listOf(DeploymentTarget(alice, "phone")), fromMs = 1_000L, toMs = 2_000L))

        assertEquals(
            1,
            rows(writes.files.getValue("measurements.csv")).size,
            writes.files.getValue("measurements.csv"),
        )
    }

    @Test
    fun `a window matching nothing fails rather than writing a header`() = runTest {
        val writes = Writes()
        val step = stepOver(seededService(), writes)

        assertFailsWith<IllegalStateException> {
            step.run(config(listOf(DeploymentTarget(alice)), fromMs = 90_000L, toMs = 99_000L))
        }

        assertTrue(writes.files.isEmpty(), "an empty result must not leave a file behind")
    }
}
