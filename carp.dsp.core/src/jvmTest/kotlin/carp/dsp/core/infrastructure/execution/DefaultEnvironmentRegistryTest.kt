package carp.dsp.core.infrastructure.execution


import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentMetadata
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

class DefaultEnvironmentRegistryTest {

    private lateinit var tmpDir: java.nio.file.Path
    private lateinit var registry: DefaultEnvironmentRegistry

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("env-registry-test")
        registry = DefaultEnvironmentRegistry(tmpDir.resolve("environments.json"))
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun registersEnvironmentMetadata() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L
        )

        registry.register(ref, metadata)

        assertTrue(registry.exists(ref.id))
    }

    @Test
    fun retrievesRegisteredMetadata() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val now = Clock.System.now()
        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = now,
            lastUsedAt = now,
            sizeBytes = 2000L
        )

        registry.register(ref, metadata)

        val retrieved = registry.getMetadata(ref.id)

        assertNotNull(retrieved)
        assertEquals("test-env", retrieved.name)
        assertEquals("conda", retrieved.kind)
        assertEquals(2000L, retrieved.sizeBytes)
    }

    @Test
    fun listsAllEnvironments() {
        val ref1 = CondaEnvironmentRef(id = "test-001", name = "env1", dependencies = emptyList())
        val ref2 = CondaEnvironmentRef(id = "test-002", name = "env2", dependencies = emptyList())

        val metadata1 = EnvironmentMetadata(
            id = ref1.id,
            name = "env1",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L
        )

        val metadata2 = EnvironmentMetadata(
            id = ref2.id,
            name = "env2",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 2000L
        )

        registry.register(ref1, metadata1)
        registry.register(ref2, metadata2)

        val list = registry.list()

        assertEquals(2, list.size)
    }

    @Test
    fun removesEnvironment() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L
        )

        registry.register(ref, metadata)
        assertTrue(registry.exists(ref.id))

        registry.remove(ref.id)
        assertFalse(registry.exists(ref.id))
    }

    @Test
    fun persistsToFile() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L
        )

        registry.register(ref, metadata)

        // File should be created
        assertTrue(tmpDir.resolve("environments.json").toFile().exists())
    }

    @Test
    fun loadsFromFile() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L
        )

        registry.register(ref, metadata)

        // Create new registry from same file
        val registry2 = DefaultEnvironmentRegistry(tmpDir.resolve("environments.json"))

        // Should have loaded the environment
        assertTrue(registry2.exists(ref.id))
        assertNotNull(registry2.getMetadata(ref.id))
    }

    @Test
    fun handlesNonexistentEnvironment() {
        assertFalse(registry.exists("nonexistent-id"))

        val metadata = registry.getMetadata("nonexistent-id")
        assertEquals(metadata, null)
    }

    @Test
    fun updateReuseCount() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        var metadata = EnvironmentMetadata(
            id = ref.id,
            name = "test-env",
            kind = "conda",
            runId = "run-001",
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 1000L,
            reuseCount = 0
        )

        registry.register(ref, metadata)

        // Update with higher reuse count
        metadata = metadata.copy(reuseCount = 5)
        registry.register(ref, metadata)

        val retrieved = registry.getMetadata(ref.id)
        assertEquals(5, retrieved?.reuseCount)
    }
}
