package carp.dsp.core.application.authoring.mapper

import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeterministicUUIDTest
{
    // ── Known-value tests (RFC 4122 v5 oracle) ────────────────────────────────

    /**
     * RFC 4122 defines a well-known DNS namespace UUID.
     * UUID v5 of the DNS namespace + "www.example.com" is a published test vector.
     * Expected: 2ed6657d-e927-568b-95e1-2665a8aea6a2
     * generated with https://www.uuidtools.com/api/generate/v5/namespace/6ba7b810-9dad-11d1-80b4-00c04fd430c8/name/base64:d3d3LmV4YW1wbGUuY29t
     */
    @Test
    fun `v5 matches RFC 4122 published test vector for DNS namespace`()
    {
        val dnsNamespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val result = DeterministicUUID.v5( dnsNamespace, "www.example.com" )
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            result.stringRepresentation,
            "UUID v5 does not match RFC 4122 test vector"
        )
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `same namespace and name always produce the same UUID`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val a = DeterministicUUID.v5( namespace, "step-001" )
        val b = DeterministicUUID.v5( namespace, "step-001" )
        assertEquals(a, b)
    }

    @Test
    fun `determinism holds across multiple calls with different names`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val names = listOf("env-conda-001", "env-pixi-002", "step-001", "step-002", "task-abc")
        names.forEach { name ->
            assertEquals(
                DeterministicUUID.v5( namespace, name ),
                DeterministicUUID.v5( namespace, name ),
                "Non-deterministic result for name: $name"
            )
        }
    }

    // ── Uniqueness ────────────────────────────────────────────────────────────

    @Test
    fun `different names in same namespace produce different UUIDs`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val a = DeterministicUUID.v5( namespace, "step-001" )
        val b = DeterministicUUID.v5( namespace, "step-002" )
        assertNotEquals(a, b)
    }

    @Test
    fun `same name in different namespaces produces different UUIDs`()
    {
        val ns1 = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val ns2 = UUID( "6ba7b811-9dad-11d1-80b4-00c04fd430c8" )
        val a = DeterministicUUID.v5( ns1, "my-step" )
        val b = DeterministicUUID.v5( ns2, "my-step" )
        assertNotEquals(a, b)
    }

    @Test
    fun `empty string name produces a deterministic UUID distinct from non-empty`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val empty = DeterministicUUID.v5( namespace, "" )
        val nonEmpty = DeterministicUUID.v5( namespace, "x" )
        assertEquals(empty, DeterministicUUID.v5( namespace, "" ))
        assertNotEquals(empty, nonEmpty)
    }

    // ── Version and variant bits (RFC 4122 structural checks) ─────────────────

    @Test
    fun `result UUID has version 5`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val uuid = DeterministicUUID.v5( namespace, "test" )
        // Version nibble is the high nibble of the 7th byte (index 14-15 in the hex string
        // after stripping dashes: positions 12-13 of the third group).
        val hex = uuid.stringRepresentation.replace("-", "")
        val versionNibble = hex[12].digitToInt(16)
        assertEquals(5, versionNibble, "Expected UUID version 5, got $versionNibble")
    }

    @Test
    fun `result UUID has RFC 4122 variant bits (10xx)`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val uuid = DeterministicUUID.v5( namespace, "test" )
        // Variant is encoded in the high 2 bits of byte 8 (first byte of 4th group).
        val hex = uuid.stringRepresentation.replace("-", "")
        val variantByte = hex.substring(16, 18).toInt(16)
        // RFC 4122 variant: top 2 bits must be 10 → value in range 0x80..0xBF
        assertTrue(
            variantByte in 0x80..0xBF,
            "Expected RFC 4122 variant (0x80–0xBF), got 0x${"%02x".format(variantByte)}"
        )
    }

    // ── Output format ─────────────────────────────────────────────────────────

    @Test
    fun `result is a valid UUID string representation`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val uuid = DeterministicUUID.v5( namespace, "workflow-step" )
        val pattern = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
        assertTrue(
            pattern.matches( uuid.stringRepresentation ),
            "UUID string '${uuid.stringRepresentation}' does not match expected format"
        )
    }

    @Test
    fun `result UUID string is lowercase`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val uuid = DeterministicUUID.v5( namespace, "CaseSensitiveName" )
        assertEquals(
            uuid.stringRepresentation,
            uuid.stringRepresentation.lowercase(),
            "UUID string representation should be lowercase"
        )
    }

    // ── Case sensitivity of name ──────────────────────────────────────────────

    @Test
    fun `names differing only in case produce different UUIDs`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val lower = DeterministicUUID.v5( namespace, "step-001" )
        val upper = DeterministicUUID.v5( namespace, "STEP-001" )
        assertNotEquals(lower, upper, "Name comparison should be case-sensitive")
    }

    // ── Unicode / UTF-8 name encoding ─────────────────────────────────────────

    @Test
    fun `unicode names produce deterministic UUIDs`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val a = DeterministicUUID.v5( namespace, "стъпка-001" )
        val b = DeterministicUUID.v5( namespace, "стъпка-001" )
        assertEquals(a, b)
    }

    @Test
    fun `unicode names produce different UUIDs from their ASCII equivalents`()
    {
        val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
        val unicode = DeterministicUUID.v5( namespace, "стъпка" )
        val ascii = DeterministicUUID.v5( namespace, "step" )
        assertNotEquals(unicode, ascii)
    }
}

