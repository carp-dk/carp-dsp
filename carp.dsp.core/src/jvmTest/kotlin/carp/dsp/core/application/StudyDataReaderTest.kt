package carp.dsp.core.application

import carp.dsp.core.domain.data.StepCountMeasurementRow
import dk.cachet.carp.common.application.UUID
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

class StudyDataReaderTest {

    private val studyId = UUID.randomUUID()
    private val deploymentId = UUID.randomUUID()
    private val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1000L), 1000000L)

    private fun request(targets: List<DeploymentTarget> = emptyList()) =
        StudyDataRequest(studyId = studyId, targets = targets)

    private fun batchWithSteps(steps: Int): DataStreamBatch {
        val batch = MutableDataStreamBatch()
        val sequence = MutableDataStreamSequence<StepCount>(
            dataStreamId<StepCount>(deploymentId, "phone"), 100L, listOf(1), syncPoint
        )
        sequence.appendMeasurements(measurement(StepCount(steps), 1000L, 2000L))
        batch.appendSequence(sequence)
        return batch
    }

    @Test
    fun `what the source returns comes back as typed rows`() = runTest {
        val reader = StudyDataReader({ _, _ -> batchWithSteps(1500) })

        val data = reader.read(request(listOf(DeploymentTarget(deploymentId))))

        assertEquals(1, data.size)
        val row = data.rows.single()
        assertTrue(row is StepCountMeasurementRow)
        assertEquals(1500, row.steps)
    }

    @Test
    fun `authored targets are passed through untouched`() = runTest {
        val authored = listOf(
            DeploymentTarget(deploymentId, "phone"),
            DeploymentTarget(UUID.randomUUID(), "watch"),
        )
        var seen: List<DeploymentTarget>? = null
        val reader = StudyDataReader({ _, targets ->
            seen = targets
        MutableDataStreamBatch()
        })

        reader.read(request(authored))

        // Pairing is the point: a deployment and its role travel together, and
        // nothing between here and the source may flatten them into two sets.
        assertEquals(authored, seen)
    }

    @Test
    fun `a request with no targets resolves them from the study`() = runTest {
        val resolved = setOf(deploymentId, UUID.randomUUID())
        var seen: List<DeploymentTarget>? = null
        val reader = StudyDataReader(
            source = { _, targets ->
                seen = targets
            MutableDataStreamBatch()
            },
            deployments = { _ -> resolved },
        )

        reader.read(request())

        // Resolved deployments carry no role, so every role is read.
        assertEquals(resolved, seen?.map { it.studyDeploymentId }?.toSet())
        assertTrue(seen!!.all { it.deviceRoleName == null })
    }

    @Test
    fun `a study with nothing deployed fails by name`() = runTest {
        val reader = StudyDataReader(
            source = { _, _ -> MutableDataStreamBatch() },
            deployments = { _ -> emptySet() },
        )

        val failure = assertFailsWith<IllegalStateException> { reader.read(request()) }
        assertTrue(failure.message!!.contains(studyId.toString()))
    }

    @Test
    fun `resolving is refused when there is nothing to resolve with`() = runTest {
        val reader = StudyDataReader({ _, _ -> MutableDataStreamBatch() })

        assertFailsWith<IllegalStateException> { reader.read(request()) }
    }

    @Test
    fun `an empty batch reads as no rows, not as a failure`() = runTest {
        val reader = StudyDataReader({ _, _ -> MutableDataStreamBatch() })

        val data = reader.read(request(listOf(DeploymentTarget(deploymentId))))

        assertTrue(data.isEmpty)
    }

    @Test
    fun `a window that ends before it starts is rejected where it is built`() {
        assertFailsWith<IllegalArgumentException> {
            StudyDataRequest(studyId = studyId, fromMs = 2000L, toMs = 1000L)
        }
    }
}
