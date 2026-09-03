@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The step reproduces the fixture it publishes. */
class DecodeCarpDataStreamsFixtureTest {

    private fun fixture(name: String): File =
        File(
            checkNotNull(
                javaClass.classLoader.getResource("steps/core/io/decode-carp-data-streams/reference/$name")
            ) { "the step's reference fixture is not on the classpath: $name" }
                .toURI()
        )

    /** Line endings are normalised: git may check the fixture out with CRLF. */
    private fun String.lines() = replace("\r\n", "\n")

    @Test
    fun `the step writes the fixture it publishes`() {
        val measurements = File.createTempFile("fixture", ".csv").apply { delete(); deleteOnExit() }

        val rows = DecodeCarpDataStreamsStep().run(fixture("input.csv"), measurements)

        assertEquals(2L, rows)
        assertEquals(fixture("expected.csv").readText().lines(), measurements.readText().lines())
    }
}
