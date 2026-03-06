package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvironmentImporterTest
{
    private val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )

    // ── Helpers

    private fun condaDesc(
        name: String = "test-env",
        dependencies: List<String> = listOf("numpy"),
        pythonVersion: String = "3.11",
        channels: List<String> = listOf("conda-forge"),
        extras: Map<String, List<String>> = emptyMap(),
    ) = EnvironmentDescriptor(
        name = name,
        kind = "conda",
        spec = mapOf(
            "dependencies" to dependencies,
            "pythonVersion" to listOf(pythonVersion),
            "channels" to channels,
        ) + extras,
    )

    private fun pixiDesc(
        name: String = "pixi-env",
        dependencies: List<String> = listOf("scipy"),
        pythonVersion: String = "3.12",
        channels: List<String> = listOf("conda-forge"),
    ) = EnvironmentDescriptor(
        name = name,
        kind = "pixi",
        spec = mapOf(
            "dependencies" to dependencies,
            "pythonVersion" to listOf(pythonVersion),
            "channels" to channels,
        ),
    )

    // ── importEnvironment: conda ──────────────────────────────────────────────

    @Test
    fun `importEnvironment maps conda kind to CondaEnvironmentDefinition`()
    {
        val id = UUID.randomUUID()
        val result = EnvironmentImporter.importEnvironment( id, condaDesc() )
        assertIs<CondaEnvironmentDefinition>( result )
    }

    @Test
    fun `importEnvironment preserves conda id and name`()
    {
        val id = UUID.randomUUID()
        val result = EnvironmentImporter.importEnvironment( id, condaDesc( name = "my-conda" ) )
        assertEquals( id, result.id )
        assertEquals( "my-conda", result.name )
    }

    @Test
    fun `importEnvironment maps conda dependencies`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<CondaEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, condaDesc( dependencies = listOf("numpy", "pandas") ) )
        )
        assertEquals( listOf("numpy", "pandas"), result.dependencies )
    }

    @Test
    fun `importEnvironment maps conda pythonVersion`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<CondaEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, condaDesc( pythonVersion = "3.12" ) )
        )
        assertEquals( "3.12", result.pythonVersion )
    }

    @Test
    fun `importEnvironment maps conda channels`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<CondaEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, condaDesc( channels = listOf("conda-forge", "defaults") ) )
        )
        assertEquals( listOf("conda-forge", "defaults"), result.channels )
    }

    @Test
    fun `importEnvironment defaults conda pythonVersion to 3_11 when absent`()
    {
        val id = UUID.randomUUID()
        val d = EnvironmentDescriptor( name = "e", kind = "conda" )
        val result = assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( id, d ) )
        assertEquals( "3.11", result.pythonVersion )
    }

    @Test
    fun `importEnvironment defaults conda channels to conda-forge when absent`()
    {
        val id = UUID.randomUUID()
        val d = EnvironmentDescriptor( name = "e", kind = "conda" )
        val result = assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( id, d ) )
        assertEquals( listOf("conda-forge"), result.channels )
    }

    @Test
    fun `importEnvironment defaults conda dependencies to empty when absent`()
    {
        val id = UUID.randomUUID()
        val d = EnvironmentDescriptor( name = "e", kind = "conda" )
        val result = EnvironmentImporter.importEnvironment( id, d )
        assertEquals( emptyList(), result.dependencies )
    }

    // ── importEnvironment: pixi ───────────────────────────────────────────────

    @Test
    fun `importEnvironment maps pixi kind to PixiEnvironmentDefinition`()
    {
        val id = UUID.randomUUID()
        val result = EnvironmentImporter.importEnvironment( id, pixiDesc() )
        assertIs<PixiEnvironmentDefinition>( result )
    }

    @Test
    fun `importEnvironment preserves pixi id name and dependencies`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<PixiEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, pixiDesc( name = "my-pixi", dependencies = listOf("scipy", "mne") ) )
        )
        assertEquals( id, result.id )
        assertEquals( "my-pixi", result.name )
        assertEquals( listOf("scipy", "mne"), result.dependencies )
    }

    @Test
    fun `importEnvironment maps pixi pythonVersion`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<PixiEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, pixiDesc( pythonVersion = "3.13" ) )
        )
        assertEquals( "3.13", result.pythonVersion )
    }

    @Test
    fun `importEnvironment maps pixi channels`()
    {
        val id = UUID.randomUUID()
        val result = assertIs<PixiEnvironmentDefinition>(
            EnvironmentImporter.importEnvironment( id, pixiDesc( channels = listOf("conda-forge", "bioconda") ) )
        )
        assertEquals( listOf("conda-forge", "bioconda"), result.channels )
    }

    // ── importEnvironment: kind matching is case-insensitive ──────────────────

    @Test
    fun `importEnvironment accepts uppercase CONDA kind`()
    {
        val d = condaDesc().copy( kind = "CONDA" )
        assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( UUID.randomUUID(), d ) )
    }

    @Test
    fun `importEnvironment accepts mixed-case Pixi kind`()
    {
        val d = pixiDesc().copy( kind = "Pixi" )
        assertIs<PixiEnvironmentDefinition>( EnvironmentImporter.importEnvironment( UUID.randomUUID(), d ) )
    }

    // ── importEnvironment: environmentVariables from env. prefix ─────────────

    @Test
    fun `importEnvironment extracts env-prefixed spec keys as environmentVariables`()
    {
        val id = UUID.randomUUID()
        val d = condaDesc(
            extras = mapOf(
                "env.MY_VAR" to listOf("hello"),
                "env.ANOTHER" to listOf("world"),
            )
        )
        val result = assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( id, d ) )
        assertEquals( mapOf("MY_VAR" to "hello", "ANOTHER" to "world"), result.environmentVariables )
    }

    @Test
    fun `importEnvironment produces empty environmentVariables when no env-prefixed keys present`()
    {
        val id = UUID.randomUUID()
        val result = EnvironmentImporter.importEnvironment( id, condaDesc() )
        assertEquals( emptyMap(), result.environmentVariables )
    }

    @Test
    fun `importEnvironment uses first element of env-prefixed list value`()
    {
        val id = UUID.randomUUID()
        val d = condaDesc( extras = mapOf("env.KEY" to listOf("first", "ignored")) )
        val result = assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( id, d ) )
        assertEquals( "first", result.environmentVariables["KEY"] )
    }

    @Test
    fun `importEnvironment does not include env-prefixed keys in other spec fields`()
    {
        val id = UUID.randomUUID()
        val d = condaDesc( extras = mapOf("env.PATH" to listOf("/usr/bin")) )
        val result = assertIs<CondaEnvironmentDefinition>( EnvironmentImporter.importEnvironment( id, d ) )
        // env.PATH must appear in environmentVariables, NOT in dependencies/channels
        assertEquals( listOf("numpy"), result.dependencies )
        assertTrue( result.environmentVariables.containsKey("PATH") )
    }

    // ── importEnvironment: unknown kind ───────────────────────────────────────

    @Test
    fun `importEnvironment throws UnsupportedEnvironmentKindException for unknown kind`()
    {
        val d = EnvironmentDescriptor( name = "alien", kind = "docker" )
        assertFailsWith<UnsupportedEnvironmentKindException> {
            EnvironmentImporter.importEnvironment( UUID.randomUUID(), d )
        }
    }

    @Test
    fun `importEnvironment exception message contains the offending kind`()
    {
        val d = EnvironmentDescriptor( name = "alien", kind = "docker" )
        val ex = assertFailsWith<UnsupportedEnvironmentKindException> {
            EnvironmentImporter.importEnvironment( UUID.randomUUID(), d )
        }
        assertTrue( ex.message!!.contains("docker"), "Exception message should contain offending kind" )
    }

    // ── importEnvironments: UUID key resolution ───────────────────────────────

    @Test
    fun `importEnvironments parses valid UUID string keys directly`()
    {
        val envId = UUID.randomUUID()
        val envs = mapOf( envId.toString() to condaDesc() )

        val ( domainMap, keyToUuid ) = EnvironmentImporter.importEnvironments( envs, namespace )

        assertEquals( envId, keyToUuid[envId.toString()] )
        assertNotNull( domainMap[envId] )
    }

    @Test
    fun `importEnvironments generates deterministic UUID for human-readable key`()
    {
        val envs = mapOf( "env-conda-001" to condaDesc() )

        val ( _, keyToUuid1 ) = EnvironmentImporter.importEnvironments( envs, namespace )
        val ( _, keyToUuid2 ) = EnvironmentImporter.importEnvironments( envs, namespace )

        assertEquals(
            keyToUuid1["env-conda-001"],
            keyToUuid2["env-conda-001"],
            "Human-readable key should resolve to same UUID across calls"
        )
    }

    @Test
    fun `importEnvironments generated UUID for human-readable key is non-null`()
    {
        val envs = mapOf( "env-pixi-002" to pixiDesc() )
        val ( _, keyToUuid ) = EnvironmentImporter.importEnvironments( envs, namespace )
        assertNotNull( keyToUuid["env-pixi-002"] )
    }

    @Test
    fun `importEnvironments different human-readable keys produce different UUIDs`()
    {
        val envs = mapOf(
            "env-conda-001" to condaDesc(),
            "env-pixi-002" to pixiDesc(),
        )

        val ( _, keyToUuid ) = EnvironmentImporter.importEnvironments( envs, namespace )

        assertNotEquals( keyToUuid["env-conda-001"], keyToUuid["env-pixi-002"] )
    }

    @Test
    fun `importEnvironments generated UUIDs differ across namespaces`()
    {
        val otherNamespace = UUID( "6ba7b811-9dad-11d1-80b4-00c04fd430c8" )
        val envs = mapOf( "env-conda-001" to condaDesc() )

        val ( _, keyToUuidA ) = EnvironmentImporter.importEnvironments( envs, namespace )
        val ( _, keyToUuidB ) = EnvironmentImporter.importEnvironments( envs, otherNamespace )

        assertNotEquals( keyToUuidA["env-conda-001"], keyToUuidB["env-conda-001"] )
    }

    @Test
    fun `importEnvironments domain map contains entry for every input key`()
    {
        val envs = mapOf(
            "env-conda-001" to condaDesc(),
            "env-pixi-002" to pixiDesc(),
        )

        val ( domainMap, keyToUuid ) = EnvironmentImporter.importEnvironments( envs, namespace )

        assertEquals( 2, domainMap.size )
        envs.keys.forEach { key ->
            val uuid = keyToUuid[key]
            assertNotNull( uuid, "Missing UUID for key: $key" )
            assertNotNull( domainMap[uuid], "Missing domain entry for key: $key" )
        }
    }

    @Test
    fun `importEnvironments returns empty maps for empty input`()
    {
        val ( domainMap, keyToUuid ) = EnvironmentImporter.importEnvironments( emptyMap(), namespace )
        assertEquals( emptyMap(), domainMap )
        assertEquals( emptyMap(), keyToUuid )
    }
}

