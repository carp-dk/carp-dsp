package carp.dsp.core.application.environment

import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixiEnvironmentTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun serialization_round_trip_preserves_all_fields() {
        val original = PixiEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "pixi-env",
            dependencies = listOf("numpy", "pip:matplotlib"),
            environmentVariables = mapOf("FOO" to "BAR"),
            pythonVersion = "3.12",
            featureDeps = mapOf("plot" to listOf("matplotlib")),
            channels = listOf("conda-forge", "bioconda")
        )
        val encoded = json.encodeToString(PixiEnvironmentDefinition.serializer(), original)
        val decoded = json.decodeFromString(PixiEnvironmentDefinition.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun supports_empty_dependencies_and_features() {
        val env = PixiEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "empty-env",
            dependencies = emptyList(),
            featureDeps = emptyMap()
        )
        assertTrue(env.dependencies.isEmpty())
        assertTrue(env.featureDeps.isEmpty())
    }
}

