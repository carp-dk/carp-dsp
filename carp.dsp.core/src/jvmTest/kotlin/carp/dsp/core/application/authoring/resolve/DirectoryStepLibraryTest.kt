package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryStepLibraryTest
{
    private val codec = WorkflowYamlCodec()
    private val environment = EnvironmentDescriptor( name = "Python", kind = "pixi" )
    private val step = DefinedStepDescriptor(
        id = "clean",
        environmentId = "env-python",
        task = CommandTaskDescriptor( name = "clean", executable = "python" ),
    )

    /** Writes `sensing/heartrate/clean/step.yaml` under a fresh temp root and returns the root. */
    private fun writeLibrary(): File
    {
        val root = Files.createTempDirectory( "steplib" ).toFile()
        val dir = root.resolve( "sensing/heartrate/clean" ).apply { mkdirs() }
        val descriptor = WorkflowDescriptor(
            metadata = WorkflowMetadataDescriptor( id = "sensing.heartrate.clean", name = "Clean", version = "1.0" ),
            steps = listOf( step ),
            environments = mapOf( "env-python" to environment ),
        )
        dir.resolve( "step.yaml" ).writeText( codec.encode( descriptor ) )
        dir.resolve( "impl/python" ).apply { mkdirs() }.resolve( "clean.py" ).writeText( "print('clean')\n" )
        return root
    }

    @Test
    fun `looks up a step by its dotted id`()
    {
        val result = DirectoryStepLibrary( writeLibrary() ).lookup( "sensing.heartrate.clean", null )
        assertNotNull( result )
        assertEquals( "1.0", result.version )
        assertEquals( "clean", result.step.id )
        assertEquals( "env-python", result.step.environmentId )
        assertEquals( "clean", ( result.step.task as CommandTaskDescriptor ).name )
        assertEquals( setOf( "env-python" ), result.environments.keys )
        assertTrue( result.contentHash.isNotBlank(), "a content hash is computed" )
        assertEquals( "print('clean')\n", result.implFiles["impl/python/clean.py"], "impl files are read" )
    }

    @Test
    fun `content hash is stable across lookups`()
    {
        val root = writeLibrary()
        val first = DirectoryStepLibrary( root ).lookup( "sensing.heartrate.clean", null )
        val second = DirectoryStepLibrary( root ).lookup( "sensing.heartrate.clean", null )
        assertEquals( first?.contentHash, second?.contentHash )
    }

    @Test
    fun `returns null for an unknown id`()
    {
        assertNull( DirectoryStepLibrary( writeLibrary() ).lookup( "sensing.heartrate.missing", null ) )
    }

    @Test
    fun `returns the step for a matching explicit version`()
    {
        assertNotNull( DirectoryStepLibrary( writeLibrary() ).lookup( "sensing.heartrate.clean", "1.0" ) )
    }

    @Test
    fun `returns null for a mismatched explicit version`()
    {
        assertNull( DirectoryStepLibrary( writeLibrary() ).lookup( "sensing.heartrate.clean", "2.0" ) )
    }
}
