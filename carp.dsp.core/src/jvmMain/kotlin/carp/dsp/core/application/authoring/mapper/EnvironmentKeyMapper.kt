package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.common.application.UUID

/**
 * Converts descriptor environment keys (human-readable strings) to UUID-based
 * domain model with deterministic ID generation.
 *
 * **Purpose:**
 * WorkflowDescriptor uses human-readable keys like "env-conda-001" for environments.
 * Domain model uses UUIDs. This mapper bridges the two, enabling:
 * - Deterministic UUID generation (reproducible imports)
 * - Human-readable key lookup (for cross-references)
 * - Centralized environment import logic
 *
 * **Usage:**
 * ```kotlin
 * val mapper = EnvironmentKeyMapperImpl()
 * val (environments, keyToUuid) = mapper.mapEnvironmentKeys(
 *     descriptors = workflowDescriptor.environments,
 *     namespace = workflowName
 * )
 * // Now use keyToUuid to resolve environment references by key
 * ```
 *
 * **Output:** Returns both:
 * 1. environments: Map[UUID → EnvironmentDefinition] (for WorkflowDefinition)
 * 2. keyToUuid: Map[String → UUID] (for resolving step references)
 *
 * **Testability:** Can be tested independently.
 * **Reusability:** Can be used by other importers.
 */
interface EnvironmentKeyMapper
{
    /**
     * Map environment descriptor keys to domain definitions with UUIDs.
     *
     * @param descriptors Map of environment key → descriptor (from YAML)
     * @param namespace Namespace UUID for deterministic generation
     * @return Pair of:
     *   - environments: UUID → EnvironmentDefinition (for domain model)
     *   - keyToUuid: String → UUID (for step environment references)
     */
    fun mapEnvironmentKeys(
        descriptors: Map<String, EnvironmentDescriptor>,
        namespace: UUID
    ): Pair<Map<UUID, EnvironmentDefinition>, Map<String, UUID>>
}

/**
 * Default environment key mapper.
 *
 * **Process:**
 * 1. Iterate over each environment descriptor (keyed by human-readable string)
 * 2. Generate deterministic UUID v5 for the environment
 * 3. Import descriptor to domain EnvironmentDefinition
 * 4. Build two maps: UUID-based and key-based
 *
 * **UUID Generation:**
 * Uses UUID v5 with namespace and "env:{key}" to ensure reproducibility.
 * Same environment key always produces the same UUID.
 */
class EnvironmentKeyMapperImpl : EnvironmentKeyMapper
{
    override fun mapEnvironmentKeys(
        descriptors: Map<String, EnvironmentDescriptor>,
        namespace: UUID
    ): Pair<Map<UUID, EnvironmentDefinition>, Map<String, UUID>>
    {
        val environments = mutableMapOf<UUID, EnvironmentDefinition>()
        val keyToUuid = mutableMapOf<String, UUID>()

        descriptors.forEach { ( key, descriptor ) ->
            // Generate deterministic UUID for this environment
            val envUuid = DeterministicUUID.v5( namespace, "env:$key" )

            // Import descriptor to domain model
            // FIXED: Parameter order is (id, environmentDescriptor)
            val envDef = EnvironmentImporter.importEnvironment( envUuid, descriptor )

            // Register both maps
            environments[envUuid] = envDef
            keyToUuid[key] = envUuid
        }

        return environments to keyToUuid
    }
}

/**
 * Mock implementation for testing.
 *
 * Allows tests to control UUID generation and environment imports.
 *
 * **Usage:**
 * ```kotlin
 * val mock = MockEnvironmentKeyMapper(
 *     uuidGenerator = { key -> UUID.parse("550e8400-0000-0000-0000-000000000001") }
 * )
 * val (envs, mapping) = mock.mapEnvironmentKeys(descriptors, namespace)
 * ```
 */
class MockEnvironmentKeyMapper(
    private val uuidGenerator: (String) -> UUID = { UUID.randomUUID() },
    private val environmentCreator: (String, UUID) -> EnvironmentDefinition = { _, uuid ->
        // Default: create stub environment
        object : EnvironmentDefinition
        {
            override val id: UUID = uuid
            override val name: String = "mock-env"
            override val dependencies: List<String> = emptyList()
            override val environmentVariables: Map<String, String> = emptyMap()
        }
    }
) : EnvironmentKeyMapper
{
    override fun mapEnvironmentKeys(
        descriptors: Map<String, EnvironmentDescriptor>,
        namespace: UUID
    ): Pair<Map<UUID, EnvironmentDefinition>, Map<String, UUID>>
    {
        val environments = mutableMapOf<UUID, EnvironmentDefinition>()
        val keyToUuid = mutableMapOf<String, UUID>()

        descriptors.forEach { ( key, _ ) ->
            val envUuid = uuidGenerator( key )
            val envDef = environmentCreator( key, envUuid )

            environments[envUuid] = envDef
            keyToUuid[key] = envUuid
        }

        return environments to keyToUuid
    }
}
