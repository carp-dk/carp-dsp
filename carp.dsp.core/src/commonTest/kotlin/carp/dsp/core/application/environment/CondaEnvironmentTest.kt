package carp.dsp.core.application.environment

import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
}

