package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UsesResolverTest
{
    private val environment = EnvironmentDescriptor( name = "Python", kind = "pixi" )

    private val libraryStep = DefinedStepDescriptor(
        id = "clean",
        environmentId = "env-python",
        task = CommandTaskDescriptor( name = "clean", executable = "python" ),
        inputs = listOf( DataPortDescriptor( id = "raw-hr", descriptor = DataDescriptor( fileFormat = "csv" ) ) ),
        outputs = listOf( DataPortDescriptor( id = "clean-hr", descriptor = DataDescriptor( fileFormat = "csv" ) ) ),
    )

    /** Fake library: echoes the requested version, fixed content hash. */
    private val library = StepLibrary { id, version ->
        if ( id == "sensing.heartrate.clean" )
            LibraryStep(
                version = version ?: "1.0",
                step = libraryStep,
                environments = mapOf( "env-python" to environment ),
                contentHash = "abc123",
                implFiles = mapOf( "impl/python/clean.py" to "print('clean')\n" ),
            )
        else null
    }

    private fun workflow( vararg steps: StepDescriptor ) =
        WorkflowDescriptor( metadata = WorkflowMetadataDescriptor( name = "w" ), steps = steps.toList() )

    private val reference = ReferencedStepDescriptor(
        id = "clean-hr",
        uses = "sensing.heartrate.clean",
        dependsOn = listOf( "load" ),
        inputs = listOf(
            DataPortDescriptor( id = "raw-hr", source = StepOutputInputSource( stepId = "load", outputId = "hr" ) )
        ),
    )

    // ── Expansion ─────────────────────────────────────────────────────────────

    @Test
    fun `expands a referenced step, taking definition from the library`()
    {
        val step = UsesResolver( library ).resolve( workflow( reference ) )
            .workflow.steps.single() as DefinedStepDescriptor

        assertEquals( "clean-hr", step.id, "consumer id wins" )
        assertEquals( listOf( "load" ), step.dependsOn, "consumer ordering wins" )
        assertEquals( "env-python", step.environmentId, "environment comes from the library" )
        assertEquals( libraryStep.task, step.task, "task comes from the library" )
        assertEquals( libraryStep.outputs, step.outputs, "outputs come from the library" )
    }

    @Test
    fun `overlays the consumer source onto the library input port`()
    {
        val step = UsesResolver( library ).resolve( workflow( reference ) )
            .workflow.steps.single() as DefinedStepDescriptor
        val input = step.inputs.single()

        assertEquals( "raw-hr", input.id )
        assertEquals( DataDescriptor( fileFormat = "csv" ), input.descriptor, "port descriptor comes from the library" )
        assertEquals( StepOutputInputSource( "load", "hr" ), input.source, "source comes from the consumer" )
    }

    @Test
    fun `reference args overlay the library task args flag-by-flag`()
    {
        val libraryWithArgs = StepLibrary { id, _ ->
            if ( id == "sensing.heartrate.clean" )
                LibraryStep(
                    version = "1.0",
                    step = libraryStep.copy(
                        task = CommandTaskDescriptor(
                            name = "join", executable = "python",
                            args = listOf( "--input", "input.0", "--output", "output.0", "--how", "inner" ),
                        )
                    ),
                    environments = mapOf( "env-python" to environment ),
                    contentHash = "abc123",
                )
            else null
        }
        val ref = reference.copy( args = listOf( "--how", "left", "--on", "participant_id" ) )

        val step = UsesResolver( libraryWithArgs ).resolve( workflow( ref ) )
            .workflow.steps.single() as DefinedStepDescriptor

        assertEquals(
            listOf( "--input", "input.0", "--output", "output.0", "--how", "left", "--on", "participant_id" ),
            ( step.task as CommandTaskDescriptor ).args,
            "matching --how is overridden; --input/--output kept; --on appended",
        )
    }

    @Test
    fun `merges the library environment into the workflow`()
    {
        val resolved = UsesResolver( library ).resolve( workflow( reference ) ).workflow
        assertEquals( environment, resolved.environments["env-python"] )
    }

    @Test
    fun `leaves a defined step untouched`()
    {
        val defined = DefinedStepDescriptor(
            id = "inline",
            environmentId = "env-python",
            task = CommandTaskDescriptor( name = "t", executable = "echo" ),
        )
        val resolved = UsesResolver( library ).resolve( workflow( defined ) ).workflow
        assertSame( defined, resolved.steps.single() )
    }

    @Test
    fun `returns the same descriptor when there is nothing to resolve`()
    {
        val defined = DefinedStepDescriptor(
            environmentId = "env-python",
            task = CommandTaskDescriptor( name = "t", executable = "echo" ),
        )
        val input = workflow( defined )
        assertSame( input, UsesResolver( library ).resolve( input ).workflow )
    }

    @Test
    fun `throws when the reference is unknown`()
    {
        val unknown = ReferencedStepDescriptor( uses = "sensing.heartrate.missing" )
        val error = assertFailsWith<UsesResolutionException> {
            UsesResolver( library ).resolve( workflow( unknown ) )
        }
        assertTrue( error.message!!.contains( "sensing.heartrate.missing" ) )
    }

    @Test
    fun `throws on an environment id clash`()
    {
        val clashing = workflow( reference ).copy(
            environments = mapOf( "env-python" to EnvironmentDescriptor( name = "Other", kind = "conda" ) )
        )
        assertFailsWith<UsesResolutionException> { UsesResolver( library ).resolve( clashing ) }
    }

    // ── Lock ──────────────────────────────────────────────────────────────────

    @Test
    fun `records a lock entry for each resolved reference`()
    {
        val lock = UsesResolver( library ).resolve( workflow( reference ) ).lock
        assertEquals(
            listOf( LockedStep( "sensing.heartrate.clean", "1.0", "abc123" ) ),
            lock.steps,
        )
    }

    @Test
    fun `collects the resolved step's impl files keyed by step id`()
    {
        val result = UsesResolver( library ).resolve( workflow( reference ) )
        assertEquals(
            mapOf( "clean-hr" to mapOf( "impl/python/clean.py" to "print('clean')\n" ) ),
            result.impl,
        )
    }

    @Test
    fun `carries no impl for a defined step`()
    {
        val defined = DefinedStepDescriptor(
            id = "inline",
            environmentId = "env-python",
            task = CommandTaskDescriptor( name = "t", executable = "echo" ),
        )
        assertTrue( UsesResolver( library ).resolve( workflow( defined ) ).impl.isEmpty() )
    }

    @Test
    fun `pins the locked version on re-resolution`()
    {
        val lock = StepsLock( listOf( LockedStep( "sensing.heartrate.clean", "0.9", "abc123" ) ) )
        val result = UsesResolver( library ).resolve( workflow( reference ), lock )
        assertEquals( "0.9", result.lock.entryFor( "sensing.heartrate.clean" )?.version )
    }

    @Test
    fun `an explicit reference version overrides the lock`()
    {
        val pinned = reference.copy( version = "2.0" )
        val lock = StepsLock( listOf( LockedStep( "sensing.heartrate.clean", "0.9", "abc123" ) ) )
        val result = UsesResolver( library ).resolve( workflow( pinned ), lock )
        assertEquals( "2.0", result.lock.entryFor( "sensing.heartrate.clean" )?.version )
    }

    @Test
    fun `throws when the resolved content hash does not match the lock`()
    {
        val stale = StepsLock( listOf( LockedStep( "sensing.heartrate.clean", "1.0", "stale-hash" ) ) )
        assertFailsWith<UsesResolutionException> {
            UsesResolver( library ).resolve( workflow( reference ), stale )
        }
    }
}
