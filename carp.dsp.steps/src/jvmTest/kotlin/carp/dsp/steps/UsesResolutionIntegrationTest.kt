package carp.dsp.steps

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.resolve.UsesResolver
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check that a workflow written against the library - a `uses:`
 * reference plus wiring - resolves against the real vendored library, imports,
 * and plans without errors.
 */
class UsesResolutionIntegrationTest
{
    private val workflowYaml = """
        schemaVersion: "1.0"
        metadata:
          name: "HR clean via library"
        steps:
          - id: "clean-hr"
            uses: "sensing.heartrate.clean"
            inputs:
              - id: "raw-heart-rate"
                source:
                  type: "file"
                  path: "./raw.csv"
    """.trimIndent()

    /** Fixed namespace so step ids are deterministic, as elsewhere in the framework. */
    private val namespace = UUID.parse( "d3b7f2a0-0000-5000-8000-6d6f62676170" )

    @Test
    fun `a uses workflow resolves against the real library, imports and plans clean`()
    {
        val descriptor = WorkflowYamlCodec().decodeOrThrow( workflowYaml )
        val resolution = UsesResolver( ClasspathStepLibrary() ).resolve( descriptor )
        val resolved = resolution.workflow

        // The resolution is recorded in the lock.
        assertEquals( "sensing.heartrate.clean", resolution.lock.steps.single().uses )

        // The reference expanded into a defined step, taking the library's environment.
        val step = resolved.steps.single() as DefinedStepDescriptor
        assertEquals( "env-python-data", step.environmentId, "environment comes from the library step" )
        assertTrue( "env-python-data" in resolved.environments, "the library environment is merged into the workflow" )

        // Import succeeds only because the reference was resolved (import errors on a bare reference).
        val definition = WorkflowDescriptorImporter( namespace ).import( resolved )
        val plan = DefaultExecutionPlanner().plan( definition )

        val errors = plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
        assertTrue( errors.isEmpty(), "plan errors: ${errors.joinToString { it.message }}" )
    }
}
