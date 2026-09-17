package carp.dsp.steps.data

import dk.cachet.carp.common.application.UUID
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the entry point's wiring: arguments to services to step.
 *
 * A seeded fetch is covered by [FetchStudyDataEndToEndTest]; the entry point
 * builds its own services by name, so nothing can be seeded through it.
 */
class FetchCarpStudyDataTest {

    private val studyId = UUID.randomUUID()

    @Test
    fun `arguments are rejected before any service is built`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            fetchStudyData(listOf("--output", "out.csv"))
        }

        assertTrue(failure.message!!.contains("--study-id"), failure.message!!)
    }

    @Test
    fun `an unknown services name is rejected`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            fetchStudyData(listOf("--study-id", "$studyId", "--output", "out.csv", "--services", "remote"))
        }

        assertTrue(failure.message!!.contains("remote"), failure.message!!)
    }

    @Test
    fun `a valid configuration reaches the step and fails on an empty study`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            fetchStudyData(argsWithTarget("out.csv"))
        }

        assertTrue(failure.message!!.contains("$studyId"), failure.message!!)
    }

    @Test
    fun `nothing is written when the study is empty`() = runTest {
        val path = "build/fetch-carp-study-data-should-not-exist.csv"

        assertFailsWith<IllegalStateException> { fetchStudyData(argsWithTarget(path)) }

        assertTrue(!File(path).exists(), "a failed fetch left a file behind")
    }

    @Test
    fun `an unknown study is refused by the studies service, not read as empty`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fetchStudyData(listOf("--study-id", "$studyId", "--output", "out.csv"))
        }
    }

    private fun argsWithTarget(output: String) = listOf(
        "--study-id", "$studyId",
        "--target", "${UUID.randomUUID()}",
        "--output", output,
    )
}
