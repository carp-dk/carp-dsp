package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentMetadata
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * In-memory registry with serialization.
 *
 * Loads from file on startup.
 * Persists to file on every change.
 */
class DefaultEnvironmentRegistry(
    private val registryFile: Path
) : EnvironmentRegistry {

    private val metadata = mutableMapOf<String, EnvironmentMetadata>()

    init {
        // Load from file on startup
        if (registryFile.exists()) {
            try {
                val json = registryFile.readText()
                val data = Json.decodeFromString<Map<String, EnvironmentMetadata>>(json)
                metadata.putAll(data)
            } catch (_: Exception) {
                // Log error but continue with empty registry
            }
        }
    }

    override fun register(environmentRef: EnvironmentRef, metadata: EnvironmentMetadata) {
        this.metadata[environmentRef.id] = metadata
        persist()
    }

    override fun exists(environmentId: String): Boolean {
        return metadata.containsKey(environmentId)
    }

    override fun getMetadata(environmentId: String): EnvironmentMetadata? {
        return metadata[environmentId]
    }

    override fun list(): List<EnvironmentMetadata> {
        return metadata.values.toList()
    }

    override fun remove(environmentId: String) {
        metadata.remove(environmentId)
        persist()
    }

    private fun persist() {
        try {
            val json = Json.encodeToString(metadata)
            registryFile.writeText(json)
        } catch (_: Exception) {
            // Log error but continue
        }
    }
}
