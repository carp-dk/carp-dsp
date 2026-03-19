package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import carp.dsp.core.application.environment.REnvironmentDefinition
import carp.dsp.core.application.environment.SystemEnvironmentDefinition
import dk.cachet.carp.analytics.application.exceptions.UnsupportedEnvironmentKindException
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.common.application.UUID

/**
 * Maps [EnvironmentDescriptor] to concrete [EnvironmentDefinition] implementations.
 *
 * Spec fields use `List<String>` values:
 * - `"dependencies"` → list forwarded directly.
 * - `"pythonVersion"` → first element taken as the version string.
 * - `"channels"` → list forwarded directly.
 * - `"env.<key>"` → reconstructed as `environmentVariables`.
 *
 */
internal object EnvironmentImporter
{
    /**
     * Imports all environments from descriptors and returns domain map + lookup table.
     *
     * @param environments Map of environment descriptors (keyed by semantic ID or UUID string)
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Pair of (domain environment map, semantic-key to UUID lookup)
     */
    fun importEnvironments(
        environments: Map<String, EnvironmentDescriptor>,
        workflowNamespace: UUID
    ): Pair<Map<UUID, EnvironmentDefinition>, Map<String, UUID>>
    {
        // Build lookup: semantic key or UUID string → UUID
        val keyToUuid: Map<String, UUID> = environments.keys.associateWith { key ->
            tryParseUuid( key ) ?: DeterministicUUID.v5(workflowNamespace, "env:$key")
        }

        // Convert to domain map
        val domainMap = environments.entries.associate { ( key, desc ) ->
            val uuid = keyToUuid.getValue( key )
            uuid to importEnvironment( uuid, desc )
        }

        return domainMap to keyToUuid
    }

    /**
     * Imports a single environment descriptor to domain model.
     *
     * @param id The environment UUID (already resolved or generated)
     * @param environmentDescriptor The environment descriptor
     * @return Domain EnvironmentDefinition (CondaEnvironmentDefinition or PixiEnvironmentDefinition)
     * @throws dk.cachet.carp.analytics.application.exceptions.UnsupportedEnvironmentKindException if kind is not recognized
     */
    fun importEnvironment(
        id: UUID,
        environmentDescriptor: EnvironmentDescriptor
    ): EnvironmentDefinition
    {
        val deps = environmentDescriptor.spec["dependencies"] ?: emptyList()
        val version = environmentDescriptor.spec["pythonVersion"]?.firstOrNull() ?: "3.11"
        val channels = environmentDescriptor.spec["channels"] ?: listOf("conda-forge")
        val envVars = environmentDescriptor.spec
            .filterKeys { it.startsWith("env.") }
            .mapKeys { it.key.removePrefix("env.") }
            .mapValues { it.value.firstOrNull() ?: "" }

        return when ( environmentDescriptor.kind.lowercase() )
        {
            "conda" -> CondaEnvironmentDefinition(
                id = id,
                name = environmentDescriptor.name,
                dependencies = deps,
                environmentVariables = envVars,
                pythonVersion = version,
                channels = channels,
            )
            "pixi" -> PixiEnvironmentDefinition(
                id = id,
                name = environmentDescriptor.name,
                dependencies = deps,
                environmentVariables = envVars,
                pythonVersion = version,
                channels = channels,
            )
            "r" -> REnvironmentDefinition(
                id = id,
                name = environmentDescriptor.name,
                rVersion = environmentDescriptor.spec["rVersion"]?.firstOrNull() ?: "4.3.0",
                rPackages = environmentDescriptor.spec["rPackages"] ?: emptyList(),
                renvLockFile = environmentDescriptor.spec["renvLockFile"]?.firstOrNull(),
                installationPath = environmentDescriptor.spec["installationPath"]?.firstOrNull(),
                dependencies = deps,
                environmentVariables = envVars
            )
            "system" -> SystemEnvironmentDefinition(
                id = id,
                name = environmentDescriptor.name,
                dependencies = deps,
                environmentVariables = envVars,
            )
            else -> throw UnsupportedEnvironmentKindException(
                message = "Unsupported environment kind: '$environmentDescriptor.kind'",
                kind = environmentDescriptor.kind
            )
        }
    }
}

