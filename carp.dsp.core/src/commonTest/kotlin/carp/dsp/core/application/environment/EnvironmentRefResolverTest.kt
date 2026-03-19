package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun resolve_pixi_environment() {
        val envId = UUID.randomUUID()
        val definition = PixiEnvironmentDefinition(
            id = envId,
            name = "test-pixi-env",
            dependencies = listOf("scipy"),
            pythonVersion = "3.12",
            channels = listOf("conda-forge", "bioconda")
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
        assertTrue(ref is PixiEnvironmentRef)
        assertTrue(ref.channels.contains("bioconda"))
    }

    @Test
    fun resolve_r_environment() {
        val envId = UUID.randomUUID()
        val definition = REnvironmentDefinition(
            id = envId,
            name = "test-r-env",
            rVersion = "4.3.0",
            rPackages = listOf("dplyr", "ggplot2"),
            dependencies = listOf("pandoc")
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
        assertTrue(ref is REnvironmentRef)
        assertTrue(ref.rPackages.contains("dplyr"))
    }

    @Test
    fun resolve_r_environment_with_renv_lock() {
        val envId = UUID.randomUUID()
        val definition = REnvironmentDefinition(
            id = envId,
            name = "test-r-renv",
            rVersion = "4.4.0",
            renvLockFile = "/path/to/renv.lock",
            installationPath = "/opt/R/4.4.0"
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

        val ref = envRefs[envId]
        assertNotNull(ref)
        assertTrue(ref is REnvironmentRef)
        assertEquals(ref.renvLockFile, "/path/to/renv.lock")
        assertEquals(ref.installationPath, "/opt/R/4.4.0")
    }
}
