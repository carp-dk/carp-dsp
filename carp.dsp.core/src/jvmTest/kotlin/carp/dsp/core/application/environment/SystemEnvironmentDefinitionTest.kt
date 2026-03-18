package carp.dsp.core.application.environment

import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemEnvironmentDefinitionTest {

    private val json = Json

    @Test
    fun `defaults are empty lists and maps`() {
        val def = SystemEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "system"
        )

        assertTrue(def.dependencies.isEmpty())
        assertTrue(def.environmentVariables.isEmpty())
    }

    @Test
    fun `serializes and deserializes`() {
        val def = SystemEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "host",
            dependencies = listOf("curl", "bash"),
            environmentVariables = mapOf("PATH" to "/usr/bin", "HOME" to "/home/test")
        )

        val encoded = json.encodeToString(SystemEnvironmentDefinition.serializer(), def)
        val decoded = json.decodeFromString(SystemEnvironmentDefinition.serializer(), encoded)

        assertEquals(def, decoded)
    }
}

