package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.StepInfo
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkspaceProvisionerTest
{
    private val provisioner = WorkspaceProvisioner()

    /** A workspace rooted at a real temp dir, with a step per id. */
    private fun workspaceWith( vararg stepIds: UUID ): Pair<ExecutionWorkspace, java.nio.file.Path>
    {
        val root = Files.createTempDirectory( "provision" )
        val workspace = ExecutionWorkspace(
            runId = UUID.randomUUID(),
            executionRoot = root.toString(),
            workflowName = "wf",
            stepInfos = stepIds.mapIndexed { i, id -> id to StepInfo( id, "step$i", i ) }.toMap(),
        )
        return workspace to root
    }

    @Test
    fun `writes content and copies files into the workspace`()
    {
        val stepId = UUID.randomUUID()
        val ( workspace, root ) = workspaceWith( stepId )
        val source = Files.createTempFile( "in", ".csv" ).apply { writeText( "a,b\n1,2\n" ) }

        provisioner.provision(
            workspace,
            WorkspaceProvisioning(
                mapOf(
                    stepId to mapOf(
                        "impl/python/clean.py" to FileSource.Content( "print('clean')\n" ),
                        "raw.csv" to FileSource.Copy( source ),
                    )
                )
            ),
        )

        assertEquals( "print('clean')\n", root.resolve( "impl/python/clean.py" ).readText() )
        assertEquals( "a,b\n1,2\n", root.resolve( "raw.csv" ).readText() )
    }

    @Test
    fun `refuses a path that escapes the workspace root`()
    {
        val stepId = UUID.randomUUID()
        val ( workspace, _ ) = workspaceWith( stepId )

        assertFailsWith<IllegalArgumentException> {
            provisioner.provision(
                workspace,
                WorkspaceProvisioning( mapOf( stepId to mapOf( "../escape.txt" to FileSource.Content( "x" ) ) ) ),
            )
        }
    }

    @Test
    fun `a missing copy source is not fatal`()
    {
        val stepId = UUID.randomUUID()
        val ( workspace, root ) = workspaceWith( stepId )
        val missing = root.resolve( "nope.csv" )

        provisioner.provision(
            workspace,
            WorkspaceProvisioning( mapOf( stepId to mapOf( "in.csv" to FileSource.Copy( missing ) ) ) ),
        )

        assertFalse( root.resolve( "in.csv" ).exists(), "missing source is skipped, not created" )
    }

    @Test
    fun `two steps staging different content at the same path collide`()
    {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val ( workspace, _ ) = workspaceWith( a, b )

        assertFailsWith<IllegalStateException> {
            provisioner.provision(
                workspace,
                WorkspaceProvisioning(
                    mapOf(
                        a to mapOf( "shared.py" to FileSource.Content( "print('a')\n" ) ),
                        b to mapOf( "shared.py" to FileSource.Content( "print('b')\n" ) ),
                    )
                ),
            )
        }
    }

    @Test
    fun `two steps staging identical content at the same path is allowed`()
    {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val ( workspace, root ) = workspaceWith( a, b )

        provisioner.provision(
            workspace,
            WorkspaceProvisioning(
                mapOf(
                    a to mapOf( "shared.py" to FileSource.Content( "print('x')\n" ) ),
                    b to mapOf( "shared.py" to FileSource.Content( "print('x')\n" ) ),
                )
            ),
        )

        assertEquals( "print('x')\n", root.resolve( "shared.py" ).readText() )
    }

    @Test
    fun `an empty provisioning does nothing`()
    {
        val ( workspace, root ) = workspaceWith()

        provisioner.provision( workspace, WorkspaceProvisioning.EMPTY )

        assertFalse( root.resolve( "impl" ).exists() )
    }
}
