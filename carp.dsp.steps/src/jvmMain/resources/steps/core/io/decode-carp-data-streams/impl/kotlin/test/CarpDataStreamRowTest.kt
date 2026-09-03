@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.common.infrastructure.serialization.CustomData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CarpDataStreamRowTest {

    private val deployment = UUID.parse("4f2b8a10-0000-4000-8000-000000000001")

    private val stepCountSnapshot = """
        {
          "measurements": [
            { "sensorStartTime": 1000000,
              "data": { "__type": "dk.cachet.carp.stepcount", "steps": 1000 } },
            { "sensorStartTime": 2000000,
              "data": { "__type": "dk.cachet.carp.stepcount", "steps": 2000 } }
          ],
          "triggerIds": [1],
          "syncPoint": {
            "synchronizedOn": "1970-01-01T00:00:00Z",
            "sensorTimestampAtSyncPoint": 0,
            "relativeClockSpeed": 1.0
          }
        }
    """.trimIndent()

    private fun row(
        snapshot: String = stepCountSnapshot,
        dataType: String = "dk.cachet.carp.stepcount",
        firstSequenceId: String = "0",
    ) = carpDataStreamRow(
        CarpDataStreamColumns.ALL,
        listOf("$deployment", "phone", dataType, firstSequenceId, snapshot),
    )

    private fun refused(block: () -> Unit): String =
        assertFailsWith<IllegalArgumentException>(block = block).message.orEmpty()

    // ── The identity outside the snapshot ────────────────────────────────────

    @Test
    fun `the stream is built from the row's columns, not from the snapshot`() {
        val sequence = row().toSequence()

        assertEquals(deployment, sequence.dataStream.studyDeploymentId)
        assertEquals("phone", sequence.dataStream.deviceRoleName)
        assertEquals("dk.cachet.carp.stepcount", sequence.dataStream.dataType.toString())
    }

    @Test
    fun `the first sequence id is the column beside the snapshot`() {
        assertEquals(42L, row(firstSequenceId = "42").toSequence().firstSequenceId)
    }

    // ── What the snapshot carries ────────────────────────────────────────────

    @Test
    fun `measurements survive in order, with their values`() {
        val sequence = row().toSequence()

        assertEquals(listOf(1_000_000L, 2_000_000L), sequence.measurements.map { it.sensorStartTime })
        assertEquals(listOf(1000, 2000), sequence.measurements.map { (it.data as StepCount).steps })
    }

    @Test
    fun `trigger ids and the sync point come from the snapshot`() {
        val sequence = row().toSequence()

        assertEquals(listOf(1), sequence.triggerIds)
        assertEquals(0L, sequence.syncPoint.sensorTimestampAtSyncPoint)
        assertEquals(1.0, sequence.syncPoint.relativeClockSpeed)
    }

    @Test
    fun `a data type this module does not register becomes CustomData`() {
        // The web service registers Data subclasses carp-dsp has never heard of -
        // consent, diagnosis, date of birth. They have to survive the trip.
        val snapshot = """
            {
              "measurements": [
                { "sensorStartTime": 1000000,
                  "data": { "__type": "dk.cachet.carp.webservices.phonenumber", "number": "+45" } }
              ],
              "triggerIds": [1],
              "syncPoint": {
                "synchronizedOn": "1970-01-01T00:00:00Z",
                "sensorTimestampAtSyncPoint": 0,
                "relativeClockSpeed": 1.0
              }
            }
        """.trimIndent()

        val sequence = row(snapshot, dataType = "dk.cachet.carp.webservices.phonenumber").toSequence()

        assertTrue(sequence.measurements.single().data is CustomData)
    }

    // ── Refused, and named ───────────────────────────────────────────────────

    @Test
    fun `a snapshot that does not decode names the row it came from`() {
        val message = refused { row(snapshot = "{ not json").toSequence() }

        assertTrue(message.contains("$deployment"), message)
        assertTrue(message.contains("phone"), message)
    }

    @Test
    fun `a snapshot disagreeing with the row's data type is refused`() {
        // The column says heartrate, the measurements say stepcount. A table
        // labelled with the wrong type is worse than no table.
        assertFailsWith<IllegalArgumentException> {
            row(dataType = "dk.cachet.carp.heartrate").toSequence()
        }
    }

    @Test
    fun `a query that did not select every column says which are missing`() {
        val message = refused {
            carpDataStreamRow(listOf("study_deployment_id", "snapshot"), listOf("$deployment", "{}"))
        }

        assertTrue(message.contains("data_type"), message)
        assertTrue(message.contains("Alias the columns"), message)
    }

    @Test
    fun `an empty cell is refused rather than guessed at`() {
        val message = refused {
            carpDataStreamRow(
                CarpDataStreamColumns.ALL,
                listOf("$deployment", "phone", "dk.cachet.carp.stepcount", "0", null),
            )
        }

        assertTrue(message.contains("snapshot"), message)
    }

    @Test
    fun `a sequence id that is not a number is refused`() {
        assertTrue(refused { row(firstSequenceId = "first") }.contains("whole number"))
    }
}
