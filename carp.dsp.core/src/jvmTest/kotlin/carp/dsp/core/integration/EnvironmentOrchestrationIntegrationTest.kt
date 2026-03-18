package carp.dsp.core.integration


import carp.dsp.core.infrastructure.execution.DefaultEnvironmentOrchestrator
import carp.dsp.core.infrastructure.execution.DefaultEnvironmentRegistry
import carp.dsp.core.infrastructure.execution.EnvironmentHandlerRegistry
import carp.dsp.core.infrastructure.execution.handlers.CondaEnvironmentHandler
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.*
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

class EnvironmentOrchestrationIntegrationTest {

    private lateinit var tmpRegistry: java.nio.file.Path
    private lateinit var registry: EnvironmentRegistry
    private lateinit var orchestrator: EnvironmentOrchestrator

    @BeforeTest
    fun setup() {
        tmpRegistry = Files.createTempDirectory("env-registry-test")
        registry = DefaultEnvironmentRegistry(tmpRegistry.resolve("environments.json"))
        orchestrator = DefaultEnvironmentOrchestrator(registry)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tmpRegistry.deleteRecursively()
    }

    @Test
    fun orchestratorRoutesToCorrectHandler() {
        val conda = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val handler = EnvironmentHandlerRegistry.getHandler(conda)
        assertTrue(handler is CondaEnvironmentHandler || handler::class.simpleName?.contains("Conda") == true)
    }

    @Test
    fun orchestratorGeneratesExecutionCommand() {
        val system = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val command = orchestrator.generateExecutionCommand(
            system,
            "python script.py arg1"
        )

        assertEquals("python script.py arg1", command)
    }

    @Test
    fun registryPersistsMetadata() {
        val ref = SystemEnvironmentRef(
            id = "test-001",
            dependencies = emptyList()
        )

        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test",
            kind = "system",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 0L
        )

        registry.register(ref, metadata)
        assertTrue(registry.exists(ref.id))

        assertEquals(metadata.name, registry.getMetadata(ref.id)?.name)
    }

    @Test
    fun cleanupPolicyReuse() {
        val config = EnvironmentConfig(cleanupPolicy = CleanupPolicy.REUSE)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "test-001", dependencies = emptyList())
        val teardownSuccess = orch.teardown(ref)

        assertTrue(teardownSuccess)
    }

    @Test
    fun cleanupPolicyPurge() {
        val config = EnvironmentConfig(cleanupPolicy = CleanupPolicy.PURGE)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        // Register an environment
        val ref = SystemEnvironmentRef(id = "test-001", dependencies = emptyList())
        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test",
            kind = "system",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 0L
        )
        registry.register(ref, metadata)

        assertTrue(registry.exists(ref.id))

        // Teardown with PURGE
        orch.teardown(ref)
    }
}
