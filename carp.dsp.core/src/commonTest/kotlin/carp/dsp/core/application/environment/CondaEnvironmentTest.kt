package carp.dsp.core.application.environment

import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CondaEnvironmentTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun can_create_conda_environment_with_minimal_config() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            name = "test-env",
            id = UUID.randomUUID()
        )

        // Assert
        assertEquals("test-env", env.name)
        assertTrue(env.dependencies.isEmpty())
        assertEquals("3.11", env.pythonVersion)
        assertEquals(listOf("conda-forge"), env.channels)
    }

    @Test
    fun can_create_conda_environment_with_dependencies() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env",
            dependencies = listOf("pandas", "numpy", "pip:scikit-learn")
        )

        // Assert
        assertEquals("test-env", env.name)
        assertEquals(3, env.dependencies.size)
        assertTrue(env.dependencies.contains("pandas"))
        assertTrue(env.dependencies.contains("numpy"))
        assertTrue(env.dependencies.contains("pip:scikit-learn"))
    }

    @Test
    fun can_create_conda_environment_with_custom_python_version() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env",
            pythonVersion = "3.12"
        )

        // Assert
        assertEquals("test-env", env.name)
        assertEquals("3.12", env.pythonVersion)
    }

    @Test
    fun can_create_conda_environment_with_multiple_channels() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env",
            channels = listOf("conda-forge", "bioconda", "defaults")
        )

        // Assert
        assertEquals("test-env", env.name)
        assertEquals(3, env.channels.size)
        assertTrue(env.channels.contains("conda-forge"))
        assertTrue(env.channels.contains("bioconda"))
        assertTrue(env.channels.contains("defaults"))
    }

    @Test
    fun can_create_conda_environment_with_all_options() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "full-env",
            dependencies = listOf("pandas", "numpy", "pip:tensorflow"),
            pythonVersion = "3.10",
            channels = listOf("conda-forge", "bioconda")
        )

        // Assert
        assertEquals("full-env", env.name)
        assertEquals(3, env.dependencies.size)
        assertEquals("3.10", env.pythonVersion)
        assertEquals(2, env.channels.size)
    }

    @Test
    fun can_deserialize_conda_environment() {
        // Arrange
        val jsonString = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "name": "analysis-env",
                "dependencies": ["scipy", "matplotlib", "pip:seaborn"],
                "pythonVersion": "3.12",
                "channels": ["conda-forge", "defaults"]
            }
        """.trimIndent()

        // Act
        val env = json.decodeFromString<CondaEnvironmentDefinition>(jsonString)

        // Assert
        assertEquals("analysis-env", env.name)
        assertEquals(3, env.dependencies.size)
        assertEquals("scipy", env.dependencies[0])
        assertEquals("matplotlib", env.dependencies[1])
        assertEquals("pip:seaborn", env.dependencies[2])
        assertEquals("3.12", env.pythonVersion)
        assertEquals(2, env.channels.size)
    }

    @Test
    fun data_class_equality_works_correctly() {
        // Arrange
        val id = UUID.randomUUID()
        val env1 = CondaEnvironmentDefinition(
            id = id,
            name = "test-env",
            dependencies = listOf("pandas"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )
        val env2 = CondaEnvironmentDefinition(
            id = id,
            name = "test-env",
            dependencies = listOf("pandas"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )
        val env3 = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "different-env",
            dependencies = listOf("pandas"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )

        // Assert
        assertEquals(env1, env2) // Same values
        assertEquals(env1.hashCode(), env2.hashCode())
        assertNotEquals(env1, env3) // Different name
    }

    @Test
    fun supports_empty_dependencies_list() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "no-deps-env",
            dependencies = emptyList()
        )

        // Assert
        assertEquals("no-deps-env", env.name)
        assertTrue(env.dependencies.isEmpty())
        assertEquals(0, env.dependencies.size)
    }

    @Test
    fun supports_pip_prefix_in_dependencies() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "mixed-env",
            dependencies = listOf(
                "pandas",
                "numpy",
                "pip:tensorflow",
                "pip:torch",
                "scipy"
            )
        )

        // Assert
        assertEquals(5, env.dependencies.size)
        val pipPackages = env.dependencies.filter { it.startsWith("pip:") }
        assertEquals(2, pipPackages.size)
        assertTrue(pipPackages.contains("pip:tensorflow"))
        assertTrue(pipPackages.contains("pip:torch"))
    }

    @Test
    fun serialization_round_trip_preserves_all_fields() {
        val original = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "serialize-env",
            dependencies = listOf("pandas", "pip:scikit-learn"),
            pythonVersion = "3.12",
            channels = listOf("conda-forge", "bioconda")
        )
        val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), original)
        val decoded = json.decodeFromString(CondaEnvironmentDefinition.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun serialization_handles_default_values_correctly() {
        // Test serialization with all default values
        val withDefaults = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "default-env"
            // All other fields use defaults
        )

        val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), withDefaults)
        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

        assertEquals(withDefaults.name, decoded.name)
        assertEquals(withDefaults.id, decoded.id)
        assertEquals(emptyList(), decoded.dependencies)
        assertEquals(emptyMap(), decoded.environmentVariables)
        assertEquals("3.11", decoded.pythonVersion)
        assertEquals(listOf("conda-forge"), decoded.channels)
    }

    @Test
    fun serialization_handles_empty_collections() {
        val emptyEnv = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "empty-env",
            dependencies = emptyList(),
            environmentVariables = emptyMap(),
            channels = emptyList()
        )

        val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), emptyEnv)
        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

        assertEquals(emptyEnv, decoded)
        assertTrue(decoded.dependencies.isEmpty())
        assertTrue(decoded.environmentVariables.isEmpty())
        assertTrue(decoded.channels.isEmpty())
    }

    @Test
    fun serialization_handles_complex_environment_variables() {
        val complexEnv = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "complex-env",
            environmentVariables = mapOf(
                "CUDA_VISIBLE_DEVICES" to "0,1",
                "PYTHONPATH" to "/opt/app:/opt/lib",
                "TF_CPP_MIN_LOG_LEVEL" to "2",
                "SPECIAL_CHARS" to "!@#$%^&*()_+-={}[]|\\:;\"'<>?,./"
            )
        )

        val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), complexEnv)
        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

        assertEquals(complexEnv, decoded)
        assertEquals(4, decoded.environmentVariables.size)
        assertEquals("0,1", decoded.environmentVariables["CUDA_VISIBLE_DEVICES"])
        assertEquals("!@#$%^&*()_+-={}[]|\\:;\"'<>?,./", decoded.environmentVariables["SPECIAL_CHARS"])
    }

    @Test
    fun deserialization_handles_missing_optional_fields() {
        // JSON with only required fields
        val minimalJson = """
        {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "name": "minimal-env"
        }
        """.trimIndent()

        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(minimalJson)

        assertEquals("minimal-env", decoded.name)
        assertEquals(UUID("550e8400-e29b-41d4-a716-446655440000"), decoded.id)
        assertEquals(emptyList(), decoded.dependencies)
        assertEquals(emptyMap(), decoded.environmentVariables)
        assertEquals("3.11", decoded.pythonVersion)
        assertEquals(listOf("conda-forge"), decoded.channels)
    }

    @Test
    fun deserialization_handles_null_optional_fields() {
        // JSON with null values for optional fields (should use defaults)
        val nullFieldsJson = """
        {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "name": "null-fields-env",
            "dependencies": [],
            "environmentVariables": {}
        }
        """.trimIndent()

        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(nullFieldsJson)

        assertEquals("null-fields-env", decoded.name)
        // Should use default values when nulls are provided
        assertEquals(emptyList(), decoded.dependencies)
        assertEquals(emptyMap(), decoded.environmentVariables)
        assertEquals("3.11", decoded.pythonVersion)
        assertEquals(listOf("conda-forge"), decoded.channels)
    }

    @Test
    fun serialization_preserves_complex_dependency_formats() {
        val complexDepsEnv = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "complex-deps-env",
            dependencies = listOf(
                "python=3.11.5",
                "numpy>=1.24.0",
                "pandas<2.0.0",
                "scipy>=1.10.0,<1.11.0",
                "pip:tensorflow==2.13.0",
                "pip:torch>=2.0.0",
                "conda-forge::matplotlib",
                "bioconda::biopython"
            )
        )

        val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), complexDepsEnv)
        val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

        assertEquals(complexDepsEnv, decoded)
        assertEquals(8, decoded.dependencies.size)
        assertTrue(decoded.dependencies.contains("python=3.11.5"))
        assertTrue(decoded.dependencies.contains("scipy>=1.10.0,<1.11.0"))
        assertTrue(decoded.dependencies.contains("conda-forge::matplotlib"))
    }

    @Test
    fun serialization_handles_various_python_versions() {
        val versions = listOf("3.8", "3.9", "3.10", "3.11", "3.12", "3.13")

        versions.forEach { version ->
            val env = CondaEnvironmentDefinition(
                id = UUID.randomUUID(),
                name = "python-$version-env",
                pythonVersion = version
            )

            val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), env)
            val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

            assertEquals(version, decoded.pythonVersion)
        }
    }

    @Test
    fun serialization_handles_various_channel_configurations() {
        val channelConfigs = listOf(
            emptyList(),
            listOf("conda-forge"),
            listOf("defaults"),
            listOf("conda-forge", "bioconda"),
            listOf("conda-forge", "bioconda", "defaults", "pytorch"),
            listOf("https://custom.repo.com/channel")
        )

        channelConfigs.forEach { channels ->
            val env = CondaEnvironmentDefinition(
                id = UUID.randomUUID(),
                name = "channels-test",
                channels = channels
            )

            val encoded = json.encodeToString(CondaEnvironmentDefinition.serializer(), env)
            val decoded = json.decodeFromString<CondaEnvironmentDefinition>(encoded)

            assertEquals(channels, decoded.channels)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializer_companion_object_is_accessible() {
        // Test that the companion serializer is properly generated and accessible
        val serializer = CondaEnvironmentDefinition.serializer()
        assertNotNull(serializer)
        assertEquals("carp.dsp.core.application.environment.CondaEnvironmentDefinition", serializer.descriptor.serialName)
    }

    @Test
    fun data_class_copy_works_with_all_parameters() {
        val original = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "original",
            dependencies = listOf("pandas"),
            environmentVariables = mapOf("TEST" to "value"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )

        // Test copy with each parameter changed
        val newId = UUID.randomUUID()
        val copied = original.copy(
            id = newId,
            name = "copied",
            dependencies = listOf("numpy"),
            environmentVariables = mapOf("NEW" to "value2"),
            pythonVersion = "3.12",
            channels = listOf("bioconda")
        )

        assertEquals(newId, copied.id)
        assertEquals("copied", copied.name)
        assertEquals(listOf("numpy"), copied.dependencies)
        assertEquals(mapOf("NEW" to "value2"), copied.environmentVariables)
        assertEquals("3.12", copied.pythonVersion)
        assertEquals(listOf("bioconda"), copied.channels)
    }

    @Test
    fun toString_includes_all_fields() {
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "test-env",
            dependencies = listOf("pandas"),
            environmentVariables = mapOf("TEST" to "value"),
            pythonVersion = "3.11",
            channels = listOf("conda-forge")
        )

        val string = env.toString()
        assertTrue(string.contains("test-env"))
        assertTrue(string.contains("pandas"))
        assertTrue(string.contains("3.11"))
        assertTrue(string.contains("conda-forge"))
    }

    @Test
    fun can_create_conda_environment_with_environment_variables() {
        // Arrange & Act
        val env = CondaEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "env-vars-test",
            environmentVariables = mapOf(
                "PYTHONPATH" to "/usr/local/lib",
                "CUDA_VISIBLE_DEVICES" to "0",
                "TF_CPP_MIN_LOG_LEVEL" to "1"
            )
        )

        // Assert
        assertEquals("env-vars-test", env.name)
        assertEquals(3, env.environmentVariables.size)
        assertEquals("/usr/local/lib", env.environmentVariables["PYTHONPATH"])
        assertEquals("0", env.environmentVariables["CUDA_VISIBLE_DEVICES"])
        assertEquals("1", env.environmentVariables["TF_CPP_MIN_LOG_LEVEL"])
    }
}
