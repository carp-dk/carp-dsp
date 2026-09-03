package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyDataStepArgsTest {

    private val studyId = UUID.randomUUID()
    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()

    private fun minimal(vararg extra: String) =
        parseStudyDataArgs(listOf("--study-id", "$studyId", "--output", "out.csv") + extra)

    @Test
    fun `a study id and an output are enough`() {
        val config = minimal()

        assertEquals(studyId, config.request.studyId)
        assertEquals("out.csv", config.outputPath)
        assertEquals(StudyDataFormat.CSV, config.format)
        assertTrue(config.request.targets.isEmpty())
        assertNull(config.provenancePath)
        assertNull(config.columns)
    }

    @Test
    fun `targets repeat, and a role travels with its deployment`() {
        val config = minimal("--target", "$d1:phone", "--target", "$d2")

        assertEquals(listOf(DeploymentTarget(d1, "phone"), DeploymentTarget(d2)), config.request.targets)
    }

    @Test
    fun `data types and columns repeat`() {
        val config = minimal(
            "--data-type", "dk.cachet.carp.heartrate",
            "--data-type", "dk.cachet.carp.stepcount",
            "--column", "heart_rate.bpm",
        )

        assertEquals(setOf("dk.cachet.carp.heartrate", "dk.cachet.carp.stepcount"), config.request.dataTypes)
        assertEquals(listOf("heart_rate.bpm"), config.columns)
    }

    @Test
    fun `the window is read as epoch milliseconds`() {
        val config = minimal("--from", "1000", "--to", "2000")

        assertEquals(1000L, config.request.fromMs)
        assertEquals(2000L, config.request.toMs)
    }

    @Test
    fun `services default to in-memory and can be named`() {
        assertEquals(StudyServicesFactory.IN_MEMORY, minimal().services)
        assertEquals(StudyServicesFactory.IN_MEMORY, minimal("--services", "in-memory").services)
    }

    @Test
    fun `an unknown services name is refused, and says what is known`() {
        val failure = assertFailsWith<IllegalArgumentException> { minimal("--services", "remote") }

        assertTrue(failure.message!!.contains("remote"), failure.message!!)
        assertTrue(failure.message!!.contains("in-memory"), failure.message!!)
    }

    @Test
    fun `refresh-cache is a switch, not a value`() {
        assertTrue(minimal("--refresh-cache").request.refreshCache)
        assertTrue(!minimal().request.refreshCache)
    }

    @Test
    fun `a switch before another flag does not swallow it`() {
        val config = minimal("--refresh-cache", "--provenance-output", "prov.csv")

        assertTrue(config.request.refreshCache)
        assertEquals("prov.csv", config.provenancePath)
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    fun `a missing study id says so`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            parseStudyDataArgs(listOf("--output", "out.csv"))
        }
        assertTrue(failure.message!!.contains("--study-id"), failure.message!!)
    }

    @Test
    fun `a misspelled flag is refused rather than ignored`() {
        // Dropping it silently would produce a table quietly missing a filter.
        val failure = assertFailsWith<IllegalArgumentException> {
            minimal("--data-typ", "dk.cachet.carp.heartrate")
        }
        assertTrue(failure.message!!.contains("--data-typ"), failure.message!!)
    }

    @Test
    fun `an unsupported format is refused, and says what is supported`() {
        val failure = assertFailsWith<IllegalArgumentException> { minimal("--format", "parquet") }

        assertTrue(failure.message!!.contains("parquet"), failure.message!!)
        assertTrue(failure.message!!.contains("csv"), failure.message!!)
    }

    @Test
    fun `a single-valued flag given twice is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            minimal("--from", "1000", "--from", "2000")
        }
        assertTrue(failure.message!!.contains("--from"), failure.message!!)
    }

    @Test
    fun `a time that is not a number names the flag`() {
        val failure = assertFailsWith<IllegalArgumentException> { minimal("--from", "yesterday") }

        assertTrue(failure.message!!.contains("--from"), failure.message!!)
    }

    @Test
    fun `a target that is not a deployment id names the flag`() {
        val failure = assertFailsWith<IllegalArgumentException> { minimal("--target", "someone:phone") }

        assertTrue(failure.message!!.contains("--target"), failure.message!!)
    }

    @Test
    fun `a bare value with no flag is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            parseStudyDataArgs(listOf("out.csv"))
        }
        assertTrue(failure.message!!.contains("out.csv"), failure.message!!)
    }
}
