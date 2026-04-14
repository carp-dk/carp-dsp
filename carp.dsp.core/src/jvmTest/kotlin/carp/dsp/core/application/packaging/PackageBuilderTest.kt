package carp.dsp.core.application.packaging

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import health.workflows.interfaces.model.ValidationAssets
import health.workflows.interfaces.model.WorkflowFormat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageBuilderTest {

    private val descriptor = WorkflowDescriptor(
        metadata = WorkflowMetadataDescriptor(
            name = "Risk Scoring",
            description = "A risk scoring workflow",
            version = "1.0",
            tags = listOf("dsp", "risk"),
        ),
    )

    // ── build with definition only ────────────────────────────────────────────

    @Test
    fun `build with descriptor only returns valid package`() {
        val pkg = PackageBuilder.build(descriptor)

        assertEquals("risk-scoring", pkg.id)
        assertEquals("1.0", pkg.version)
        assertEquals(WorkflowFormat.CARP_DSP, pkg.native.format)
        assertTrue(pkg.contentHash.isNotBlank())
        assertEquals("Risk Scoring", pkg.metadata.name)
        assertEquals("A risk scoring workflow", pkg.metadata.description)
        assertNull(pkg.execution)
        assertNull(pkg.cwl)
        assertNull(pkg.validation)
    }

    @Test
    fun `native content is non-empty YAML`() {
        val pkg = PackageBuilder.build(descriptor)

        assertTrue(pkg.native.content.isNotBlank())
        assertTrue(pkg.native.content.contains("Risk Scoring"))
    }

    // ── execution field ───────────────────────────────────────────────────────

    @Test
    fun `build with execution sets execution field`() {
        val snapshot = JsonPrimitive("snapshot-placeholder")
        val pkg = PackageBuilder.build(descriptor, execution = snapshot)

        assertNotNull(pkg.execution)
        assertEquals(snapshot, pkg.execution)
    }

    // ── validation field ──────────────────────────────────────────────────────

    @Test
    fun `build with validation sets validation field`() {
        val assets = ValidationAssets(schemas = mapOf("input" to "{}"))
        val pkg = PackageBuilder.build(descriptor, validation = assets)

        assertNotNull(pkg.validation)
        assertEquals(assets, pkg.validation)
    }

    // ── contentHash ───────────────────────────────────────────────────────────

    @Test
    fun `contentHash is deterministic for same inputs`() {
        val pkg1 = PackageBuilder.build(descriptor)
        val pkg2 = PackageBuilder.build(descriptor)

        assertEquals(pkg1.contentHash, pkg2.contentHash)
    }

    @Test
    fun `contentHash differs when descriptor content changes`() {
        val other = descriptor.copy(
            metadata = descriptor.metadata.copy(name = "Different Workflow"),
        )
        val pkg1 = PackageBuilder.build(descriptor)
        val pkg2 = PackageBuilder.build(other)

        assertNotEquals(pkg1.contentHash, pkg2.contentHash)
    }

    @Test
    fun `contentHash matches SHA-256 of native content`() {
        val pkg = PackageBuilder.build(descriptor)
        val expected = sha256HexForTest(pkg.native.content)

        assertEquals(expected, pkg.contentHash)
    }

    // ── id derivation ─────────────────────────────────────────────────────────

    @Test
    fun `descriptor id used as package id when present`() {
        val withId = descriptor.copy(
            metadata = descriptor.metadata.copy(id = "my.custom.id"),
        )
        val pkg = PackageBuilder.build(withId)

        assertEquals("my.custom.id", pkg.id)
    }

    @Test
    fun `name is slugified when descriptor id absent`() {
        assertEquals("risk-scoring", descriptor.toPackageId())
    }

    @Test
    fun `slugify handles special characters`() {
        val d = descriptor.copy(
            metadata = descriptor.metadata.copy(name = "My Workflow: (v2)"),
        )
        assertEquals("my-workflow-v2", d.toPackageId())
    }
}

private fun sha256HexForTest(content: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
