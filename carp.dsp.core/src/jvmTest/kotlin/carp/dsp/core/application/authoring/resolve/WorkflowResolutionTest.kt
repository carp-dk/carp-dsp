package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowResolutionTest
{
    private val libraryStep = DefinedStepDescriptor(
        id = "clean",
        environmentId = "env-python",
        task = CommandTaskDescriptor( name = "clean", executable = "python" ),
    )

    private val library = StepLibrary { id, _ ->
        if ( id == "sensing.heartrate.clean" )
            LibraryStep(
                version = "1.0",
                step = libraryStep,
                environments = mapOf( "env-python" to EnvironmentDescriptor( name = "Python", kind = "pixi" ) ),
                contentHash = "abc123",
            )
        else null
    }

    private fun workflowFile() =
        Files.createTempDirectory( "res" ).resolve( "workflow.yaml" ).toFile().apply { writeText( "" ) }

    @Test
    fun `resolves references and writes the lock beside the workflow`()
    {
        val file = workflowFile()
        val descriptor = WorkflowDescriptor(
            metadata = WorkflowMetadataDescriptor( name = "w" ),
            steps = listOf( ReferencedStepDescriptor( id = "clean-hr", uses = "sensing.heartrate.clean" ) ),
        )

        val resolved = WorkflowResolution.resolve( file, descriptor, library )

        assertIs<DefinedStepDescriptor>( resolved.workflow.steps.single() )
        assertTrue( StepsLockFile.lockFileFor( file ).isFile )
        assertEquals( "sensing.heartrate.clean", StepsLockFile.read( file )?.steps?.single()?.uses )
    }

    @Test
    fun `an all-inline workflow writes no lock`()
    {
        val file = workflowFile()
        val descriptor = WorkflowDescriptor(
            metadata = WorkflowMetadataDescriptor( name = "w" ),
            steps = listOf(
                DefinedStepDescriptor(
                    id = "x",
                    environmentId = "e",
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                )
            ),
        )

        WorkflowResolution.resolve( file, descriptor, library )

        assertFalse( StepsLockFile.lockFileFor( file ).isFile )
    }
}
