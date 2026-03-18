package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.*
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.*

class DefaultEnvironmentOrchestratorTest {

    private lateinit var tmpDir: java.nio.file.Path
    private lateinit var registry: EnvironmentRegistry
    private lateinit var orchestrator: DefaultEnvironmentOrchestrator

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("orch-test")
        registry = DefaultEnvironmentRegistry(tmpDir.resolve("environments.json"))
        orchestrator = DefaultEnvironmentOrchestrator(registry)
    }

    @Test
    fun generateExecutionCommandForSystem() {
        val ref = SystemEnvironmentRef(id = "system-001")

        val command = orchestrator.generateExecutionCommand(ref, "python script.py")

        assertEquals("python script.py", command)
    }

    @Test
    fun generateExecutionCommandForConda() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "my-env",
            dependencies = emptyList()
        )

        val command = orchestrator.generateExecutionCommand(ref, "python script.py")

        assertTrue(command.contains("conda run -n my-env"))
        assertTrue(command.contains("python script.py"))
    }

    @Test
    fun generateExecutionCommandForPixi() {
        val ref = PixiEnvironmentRef(id = "test-001", dependencies = emptyList())

        val command = orchestrator.generateExecutionCommand(ref, "python script.py")

        assertTrue(command.contains("pixi run"))
        assertTrue(command.contains("python script.py"))
    }

    @Test
    fun setupReturnsTrue() {
        val ref = SystemEnvironmentRef(id = "system-001")

        val result = orchestrator.setup(ref)

        assertTrue(result)
    }

    @Test
    fun teardownWithReusePolicy() {
        val config = EnvironmentConfig(cleanupPolicy = CleanupPolicy.REUSE)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "system-001")

        val result = orch.teardown(ref)

        assertTrue(result)
    }

    @Test
    fun teardownWithCleanPolicy() {
        val config = EnvironmentConfig(cleanupPolicy = CleanupPolicy.CLEAN)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "system-001")

        val result = orch.teardown(ref)

        assertTrue(result)
    }

    @Test
    fun teardownWithPurgePolicy() {
        val config = EnvironmentConfig(cleanupPolicy = CleanupPolicy.PURGE)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        // Register an environment
        val ref = SystemEnvironmentRef(id = "system-001")
        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "system",
            kind = "system",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 0L
        )
        registry.register(ref, metadata)

        assertTrue(registry.exists(ref.id))

        orch.teardown(ref)

        // PURGE should remove from registry
        assertFalse(registry.exists(ref.id))
    }

    @Test
    fun registersEnvironmentAfterSetup() {
        val ref = SystemEnvironmentRef(id = "system-001")

        assertFalse(registry.exists(ref.id))

        orchestrator.setup(ref)

        assertTrue(registry.exists(ref.id))
    }

    @Test
    fun reuseExistingEnvironment() {
        val config = EnvironmentConfig(reuseExisting = true)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "system-001")

        // First setup
        orch.setup(ref)

        val metadata1 = registry.getMetadata(ref.id)
        assertNotNull(metadata1)

        val metadata2 = metadata1.copy(reuseCount = 5)
        registry.register(ref, metadata2)

        // Second setup (should reuse)
        val result = orch.setup(ref)
        assertTrue(result)

        // Reuse count should be updated from registry
        val retrieved = registry.getMetadata(ref.id)
        assertEquals(5, retrieved?.reuseCount)
    }

    @Test
    fun logsEnvironmentOperations() {
        val ref = SystemEnvironmentRef(id = "system-001")

        orchestrator.setup(ref)
        orchestrator.teardown(ref)

        val logs = orchestrator.getEnvironmentLogs()

        assertTrue(logs.setupLogs.isNotEmpty())
        assertTrue(logs.teardownLogs.isNotEmpty())
    }

    @Test
    fun logsContainCorrectPhase() {
        val ref = SystemEnvironmentRef(id = "system-001")

        orchestrator.setup(ref)

        val logs = orchestrator.getEnvironmentLogs()

        assertTrue(logs.setupLogs.any { it.phase == EnvironmentPhase.SETUP })
    }

    @Test
    fun setupTimingEager() {
        val config = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "system-001")

        orch.setup(ref)

        assertTrue(registry.exists(ref.id))
    }

    @Test
    fun setupTimingLazy() {
        val config = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        val ref = SystemEnvironmentRef(id = "system-001")

        // Should set up when requested
        orch.setup(ref)

        assertTrue(registry.exists(ref.id))
    }

    @Test
    fun errorHandlingFailFast() {
        val config = EnvironmentConfig(errorHandling = ErrorHandling.FAIL_FAST)
        val orch = DefaultEnvironmentOrchestrator(registry, config)

        // System handler won't fail, but config is set
        val ref = SystemEnvironmentRef(id = "system-001")
        val result = orch.setup(ref)

        assertTrue(result)
    }

    @Test
    fun environmentMetadataHasCorrectFields() {
        val ref = SystemEnvironmentRef(id = "system-001")

        orchestrator.setup(ref)

        val metadata = registry.getMetadata(ref.id)

        assertNotNull(metadata)
        assertEquals("system-001", metadata.id)
        assertEquals("system", metadata.kind)
        assertEquals("active", metadata.status)
    }

    @Test
    fun `run and step ids extracted from environment id`() {
        val ref = SystemEnvironmentRef(id = "run-a-b-step-42-system")

        orchestrator.setup(ref)

        val metadata = registry.getMetadata(ref.id)

        assertNotNull(metadata)
        assertEquals("run-a-b", metadata.runId)
        assertEquals("step", metadata.stepId)
    }
}
