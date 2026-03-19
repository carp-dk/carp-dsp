package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import carp.dsp.core.application.environment.SystemEnvironmentDefinition
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests environment descriptor mapping to domain definitions with deterministic UUID generation.
 * Verifies bidirectional mapping (key↔UUID) and determinism across invocations.
 */
class EnvironmentKeyMapperTest
{
    private val testNamespace = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )
    private val mapper = EnvironmentKeyMapperImpl()

    // ── Basic Mapping Tests ───────────────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys returns empty pair for empty descriptors`()
    {
        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            emptyMap(),
            testNamespace
        )

        assertTrue( environments.isEmpty() )
        assertTrue( keyToUuid.isEmpty() )
    }

    @Test
    fun `mapEnvironmentKeys maps single conda environment`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "eeg-analysis",
            kind = "conda",
            spec = mapOf(
                "dependencies" to listOf( "numpy", "scipy", "mne" ),
                "pythonVersion" to listOf( "3.11" ),
                "channels" to listOf( "conda-forge", "defaults" )
            )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-conda-001" to descriptor ),
            testNamespace
        )

        assertEquals( 1, environments.size )
        assertEquals( 1, keyToUuid.size )

        val envUuid = keyToUuid["env-conda-001"]
        assertNotNull( envUuid )

        val envDef = environments[envUuid]
        assertNotNull( envDef )
        assertTrue( envDef is CondaEnvironmentDefinition)
        assertEquals( "eeg-analysis", envDef.name )
        assertEquals( listOf( "numpy", "scipy", "mne" ), envDef.dependencies )
        assertEquals( "3.11", envDef.pythonVersion )
        assertEquals( listOf( "conda-forge", "defaults" ), envDef.channels )
    }

    @Test
    fun `mapEnvironmentKeys maps single pixi environment`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "report-gen",
            kind = "pixi",
            spec = mapOf(
                "dependencies" to listOf( "matplotlib", "jinja2" ),
                "pythonVersion" to listOf( "3.12" ),
                "channels" to listOf( "conda-forge" )
            )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-pixi-002" to descriptor ),
            testNamespace
        )

        assertEquals( 1, environments.size )
        val envUuid = keyToUuid["env-pixi-002"]
        assertNotNull( envUuid )

        val envDef = environments[envUuid]
        assertNotNull( envDef )
        assertTrue( envDef is PixiEnvironmentDefinition)
        assertEquals( "report-gen", envDef.name )
    }

    @Test
    fun `mapEnvironmentKeys maps single system environment`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "system-default",
            kind = "system",
            spec = emptyMap()
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-system-default" to descriptor ),
            testNamespace
        )

        assertEquals( 1, environments.size )
        val envUuid = keyToUuid["env-system-default"]
        assertNotNull( envUuid )

        val envDef = environments[envUuid]
        assertNotNull( envDef )
        assertTrue( envDef is SystemEnvironmentDefinition)
        assertEquals( "system-default", envDef.name )
    }

    // ── Multiple Environment Tests ────────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys maps multiple environments correctly`()
    {
        val descriptors = mapOf(
            "env-conda-001" to EnvironmentDescriptor(
                name = "conda-env",
                kind = "conda",
                spec = mapOf( "dependencies" to listOf( "numpy" ) )
            ),
            "env-pixi-002" to EnvironmentDescriptor(
                name = "pixi-env",
                kind = "pixi",
                spec = mapOf( "dependencies" to listOf( "matplotlib" ) )
            ),
            "env-system-default" to EnvironmentDescriptor(
                name = "system-env",
                kind = "system",
                spec = emptyMap()
            )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            descriptors,
            testNamespace
        )

        assertEquals( 3, environments.size )
        assertEquals( 3, keyToUuid.size )

        // Verify each key maps to something
        val uuids = listOf(
            keyToUuid["env-conda-001"],
            keyToUuid["env-pixi-002"],
            keyToUuid["env-system-default"]
        )

        uuids.forEach { uuid ->
            assertNotNull( uuid )
            assertTrue( environments.containsKey( uuid ) )
        }
    }

    // ── Determinism Tests ─────────────────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys generates deterministic UUIDs`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "test-env",
            kind = "conda",
            spec = mapOf( "dependencies" to listOf( "numpy" ) )
        )
        val descriptors = mapOf( "env-1" to descriptor )

        val ( _, keyToUuid1 ) = mapper.mapEnvironmentKeys( descriptors, testNamespace )
        val ( _, keyToUuid2 ) = mapper.mapEnvironmentKeys( descriptors, testNamespace )

        // Same input, same namespace → same UUID
        assertEquals(
            keyToUuid1["env-1"],
            keyToUuid2["env-1"],
            "Determinism: same key should produce same UUID"
        )
    }

    @Test
    fun `mapEnvironmentKeys UUID different for different namespaces`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "test-env",
            kind = "conda",
            spec = mapOf( "dependencies" to listOf( "numpy" ) )
        )
        val descriptors = mapOf( "env-1" to descriptor )

        val namespace1 = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )
        val namespace2 = UUID.parse( "b2c3d4e5-0000-0000-0000-000000000002" )

        val mapper1 = EnvironmentKeyMapperImpl()
        val mapper2 = EnvironmentKeyMapperImpl()

        val ( _, keyToUuid1 ) = mapper1.mapEnvironmentKeys( descriptors, namespace1 )
        val ( _, keyToUuid2 ) = mapper2.mapEnvironmentKeys( descriptors, namespace2 )

        // Different namespaces → different UUIDs
        assertNotEquals(
            keyToUuid1["env-1"],
            keyToUuid2["env-1"],
            "Different namespaces produce different UUIDs"
        )
    }

    @Test
    fun `mapEnvironmentKeys UUID different for different keys`()
    {
        val descriptor1 = EnvironmentDescriptor(
            name = "env-a",
            kind = "conda",
            spec = emptyMap()
        )
        val descriptor2 = EnvironmentDescriptor(
            name = "env-b",
            kind = "conda",
            spec = emptyMap()
        )

        val ( _, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf(
                "env-key-a" to descriptor1,
                "env-key-b" to descriptor2
            ),
            testNamespace
        )

        // Different keys → different UUIDs
        assertNotEquals(
            keyToUuid["env-key-a"],
            keyToUuid["env-key-b"],
            "Different keys produce different UUIDs"
        )
    }

    // ── Bidirectional Mapping Tests ───────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys provides both UUID and key mappings`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "test",
            kind = "conda",
            spec = mapOf( "dependencies" to listOf( "numpy" ) )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-test" to descriptor ),
            testNamespace
        )

        val key = "env-test"
        val uuid = keyToUuid[key]
        assertNotNull( uuid )

        val envDef = environments[uuid]
        assertNotNull( envDef )

        // Verify bidirectional mapping works
        assertEquals( uuid, keyToUuid[key], "Key maps to UUID" )
        assertEquals( envDef.name, "test", "UUID maps to EnvironmentDefinition" )
    }

    @Test
    fun `mapEnvironmentKeys no collisions between keys`()
    {
        val descriptors = (1..10).associate { i ->
            "env-$i" to EnvironmentDescriptor(
                name = "env-$i",
                kind = "conda",
                spec = emptyMap()
            )
        }

        val ( _, keyToUuid ) = mapper.mapEnvironmentKeys( descriptors, testNamespace )

        // All UUIDs should be unique
        val uuids = keyToUuid.values.toList()
        val uniqueUuids = uuids.distinct()
        assertEquals( uuids.size, uniqueUuids.size, "No UUID collisions across keys" )
    }

    // ── MockEnvironmentKeyMapper Tests ────────────────────────────────────────

    @Test
    fun `MockEnvironmentKeyMapper allows custom UUID generation`()
    {
        val fixedUuid = UUID.parse( "550e8400-e29b-41d4-a716-446655440000" )
        val mock = MockEnvironmentKeyMapper(
            uuidGenerator = { _ -> fixedUuid }
        )

        val descriptor = EnvironmentDescriptor(
            name = "test",
            kind = "conda",
            spec = emptyMap()
        )

        val ( _, keyToUuid ) = mock.mapEnvironmentKeys(
            mapOf( "env-test" to descriptor ),
            UUID.randomUUID()
        )

        assertEquals( fixedUuid, keyToUuid["env-test"] )
    }

    @Test
    fun `MockEnvironmentKeyMapper allows custom environment creation`()
    {
        val customEnv = object : EnvironmentDefinition {
            override val id = UUID.randomUUID()
            override val name = "custom-env"
            override val dependencies = listOf( "custom" )
            override val environmentVariables = mapOf( "CUSTOM_VAR" to "value" )
        }

        val mock = MockEnvironmentKeyMapper(
            environmentCreator = { _, uuid ->
                customEnv.copy(id = uuid)
            }
        )

        val descriptor = EnvironmentDescriptor(
            name = "ignored",
            kind = "conda",
            spec = emptyMap()
        )

        val ( environments, _ ) = mock.mapEnvironmentKeys(
            mapOf( "env-1" to descriptor ),
            UUID.randomUUID()
        )

        val envDef = environments.values.first()
        assertEquals( "custom-env", envDef.name )
    }

    // ── Environment Variable Tests ────────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys handles environment variables in spec`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "with-vars",
            kind = "conda",
            spec = mapOf(
                "dependencies" to listOf( "numpy" ),
                "env.HOME" to listOf( "/home/user" ),
                "env.PYTHONPATH" to listOf( "/usr/lib/python" )
            )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-with-vars" to descriptor ),
            testNamespace
        )

        val envUuid = keyToUuid["env-with-vars"]
        assertNotNull( envUuid )
        val envDef = environments[envUuid] as CondaEnvironmentDefinition

        assertEquals(
            mapOf(
            "HOME" to "/home/user",
            "PYTHONPATH" to "/usr/lib/python"
        ),
            envDef.environmentVariables
        )
    }

    @Test
    fun `mapEnvironmentKeys uses first value from multi-value spec`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "multi-value",
            kind = "conda",
            spec = mapOf(
                "pythonVersion" to listOf( "3.11", "3.12" ), // Multiple versions
                "env.TEST" to listOf( "value1", "value2" ) // Multiple values
            )
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-multi" to descriptor ),
            testNamespace
        )

        val envUuid = keyToUuid["env-multi"]
        assertNotNull( envUuid )
        val envDef = environments[envUuid] as CondaEnvironmentDefinition

        // Should use first value
        assertEquals( "3.11", envDef.pythonVersion )
        assertEquals( "value1", envDef.environmentVariables["TEST"] )
    }

    // ── Edge Cases ────────────────────────────────────────────────────────────

    @Test
    fun `mapEnvironmentKeys handles empty spec`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "empty",
            kind = "conda",
            spec = emptyMap()
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-empty" to descriptor ),
            testNamespace
        )

        val envUuid = keyToUuid["env-empty"]
        assertNotNull( envUuid )
        val envDef = environments[envUuid]
        assertNotNull( envDef )
        assertEquals( "empty", envDef.name )
    }

    @Test
    fun `mapEnvironmentKeys handles missing optional fields with defaults`()
    {
        val descriptor = EnvironmentDescriptor(
            name = "minimal",
            kind = "system",
            spec = emptyMap()
        )

        val ( environments, keyToUuid ) = mapper.mapEnvironmentKeys(
            mapOf( "env-minimal" to descriptor ),
            testNamespace
        )

        val envUuid = keyToUuid["env-minimal"]
        assertNotNull( envUuid )
        val envDef = environments[envUuid]
        assertNotNull( envDef )
        assertTrue( envDef.dependencies.isEmpty() )
        assertTrue( envDef.environmentVariables.isEmpty() )
    }

    // Helper extension for mock (in test only)
    private fun EnvironmentDefinition.copy(id: UUID): EnvironmentDefinition {
        return when (this) {
            is CondaEnvironmentDefinition -> this.copy(id = id)
            is PixiEnvironmentDefinition -> this.copy(id = id)
            is SystemEnvironmentDefinition -> this.copy(id = id)
            else -> this
        }
    }
}
