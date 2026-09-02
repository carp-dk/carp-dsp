package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceProvisioningTest
{
    @Test
    fun `EMPTY is empty`()
    {
        assertTrue( WorkspaceProvisioning.EMPTY.isEmpty )
        assertTrue( WorkspaceProvisioning().byStep.isEmpty() )
    }

    @Test
    fun `a provisioning with a step is not empty`()
    {
        val step = UUID.randomUUID()
        val provisioning = WorkspaceProvisioning(
            mapOf( step to mapOf( "impl/python/clean.py" to FileSource.Content( "print('x')\n" ) ) )
        )
        assertFalse( provisioning.isEmpty )
        assertEquals( setOf( step ), provisioning.byStep.keys )
    }

    @Test
    fun `file sources carry their bytes or their source path`()
    {
        val content = FileSource.Content( "hello" )
        val copy = FileSource.Copy( Path.of( "/tmp/in.csv" ) )

        assertEquals( "hello", content.text )
        assertEquals( Path.of( "/tmp/in.csv" ), copy.from )
    }
}
