package carp.dsp.core.application.run

import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [relativeInputPath] picks out only the inputs staged from the workflow directory. */
class RelativeInputPathTest
{
    private fun input( path: String, metadata: Map<String, String> = emptyMap() ) = InputDataSpec(
        id = UUID.randomUUID(),
        name = "in",
        location = FileLocation( path = path, format = FileFormat.CSV, metadata = metadata )
    )

    @Test
    fun `relative file is staged without its leading dot slash`()
    {
        assertEquals( "data/raw.csv", relativeInputPath( input( "./data/raw.csv" ) ) )
    }

    @Test
    fun `external input referenced by URI is not staged`()
    {
        val uri = "https://zenodo.org/record/53894"
        assertNull( relativeInputPath( input( uri, mapOf( "source" to "external", "uri" to uri ) ) ) )
    }

    @Test
    fun `any URI is not staged, even without source metadata`()
    {
        assertNull( relativeInputPath( input( "s3://bucket/raw.csv" ) ) )
    }

    @Test
    fun `protocol-bound input is not staged`()
    {
        assertNull( relativeInputPath( input( "", mapOf( "source" to "protocol" ) ) ) )
    }
}
