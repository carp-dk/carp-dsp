package carp.dsp.core.application.authoring.resolve

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepsLockFileTest
{
    private val lock = StepsLock(
        listOf(
            LockedStep( "sensing.heartrate.clean", "1.0", "abc123" ),
            LockedStep( "core.stats.summarise", "2.1", "def456" ),
        )
    )

    private fun workflowFile() =
        Files.createTempDirectory( "lock" ).resolve( "workflow.yaml" ).toFile().apply { writeText( "" ) }

    @Test
    fun `writes and reads a lock beside the workflow`()
    {
        val workflow = workflowFile()
        StepsLockFile.write( workflow, lock )

        assertTrue( StepsLockFile.lockFileFor( workflow ).isFile )
        assertEquals( lock, StepsLockFile.read( workflow ) )
    }

    @Test
    fun `read returns null when there is no lock`()
    {
        assertNull( StepsLockFile.read( workflowFile() ) )
    }
}
