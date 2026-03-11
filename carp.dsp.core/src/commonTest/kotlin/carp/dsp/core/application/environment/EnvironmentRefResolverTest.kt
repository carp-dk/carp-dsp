package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvironmentRefResolverTest {
    private val resolver = EnvironmentRefResolver()

    private class MockTask : TaskDefinition {
        override val id: UUID = UUID.randomUUID()
        override val name: String = "MockTask"
        override val description: String = "A mock task for testing"
    }

    @Test
    fun resolve_conda_environment() {
        val envId = UUID.randomUUID()
        val definition = CondaEnvironmentDefinition(
            id = envId,
            name = "test-conda-env",
            dependencies = listOf("numpy")
        )

        val step = Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "Test Step",
                version = Version(1, 0)
            ),
            task = MockTask(),
            environmentId = envId
        )

        val envRefs = resolver.resolveEnvironments(
            listOf(step),
            mapOf(envId to definition),
            mutableListOf()
        )

        assertTrue(envRefs.containsKey(envId))
        val ref = envRefs[envId]
        assertNotNull(ref)
        assertTrue(ref is CondaEnvironmentRef)
    }

    @Test
    fun missing_environment_produces_error() {
        val missingEnvId = UUID.randomUUID()

        val step = Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "Test Step",
                version = Version(1, 0)
            ),
            task = MockTask(),
            environmentId = missingEnvId
        )

        val issues = mutableListOf<PlanIssue>()
        resolver.resolveEnvironments(
            listOf(step),
            emptyMap(),
            issues
        )

        assertNotNull(issues.find { it.code == "MISSING_ENVIRONMENT_DEFINITION" })
    }
}
