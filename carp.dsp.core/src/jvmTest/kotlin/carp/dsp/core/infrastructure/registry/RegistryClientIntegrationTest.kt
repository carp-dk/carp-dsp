package carp.dsp.core.infrastructure.registry

import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.DataSensitivity
import health.workflows.interfaces.model.NativeWorkflowAsset
import health.workflows.interfaces.model.PackageMetadata
import health.workflows.interfaces.model.WorkflowArtifactPackage
import health.workflows.interfaces.model.WorkflowFormat
import health.workflows.interfaces.model.WorkflowGranularity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [RegistryClient] against a live HWF server.
 *
 * Skipped unless the `HWF_BASE_URL` environment variable is set.
 * In CI, the HWF server container is started before these tests run.
 */
class RegistryClientIntegrationTest {

    private val baseUrl = System.getenv("HWF_BASE_URL")
    private val apiKey = System.getenv("HWF_API_KEY") ?: "dev-local-key-change-in-production"

    private fun client() = RegistryClient(
        http = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        },
        baseUrl = baseUrl!!,
        apiKey = apiKey,
    )

    @Test
    fun `search returns empty list on fresh server`() = runTest {
        assumeTrue(baseUrl != null, "HWF_BASE_URL not set — skipping server integration test")
        val results = client().search(SearchQuery())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `publish then retrieve round-trip`() = runTest {
        assumeTrue(baseUrl != null, "HWF_BASE_URL not set — skipping server integration test")

        val pkg = WorkflowArtifactPackage(
            id = "ci-test-workflow",
            version = "0.1",
            contentHash = "cafebabe",
            metadata = PackageMetadata(
                name = "CI Test Workflow",
                granularity = WorkflowGranularity.TASK,
                sensitivityClass = DataSensitivity.PUBLIC,
            ),
            native = NativeWorkflowAsset(
                format = WorkflowFormat.CARP_DSP,
                content = "name: CI Test",
            ),
        )

        val result = client().publish(pkg)
        assertTrue(result.accepted)
        assertEquals("ci-test-workflow", result.id)

        val fetched = client().getComponent("ci-test-workflow", "0.1")
        assertEquals("ci-test-workflow", fetched.id)
        assertEquals("0.1", fetched.version)
    }

    @Test
    fun `published component appears in search results`() = runTest {
        assumeTrue(baseUrl != null, "HWF_BASE_URL not set — skipping server integration test")

        val pkg = WorkflowArtifactPackage(
            id = "searchable-workflow",
            version = "1.0",
            contentHash = "deadc0de",
            metadata = PackageMetadata(
                name = "Searchable Workflow",
                granularity = WorkflowGranularity.WORKFLOW,
                sensitivityClass = DataSensitivity.PUBLIC,
            ),
            native = NativeWorkflowAsset(
                format = WorkflowFormat.CARP_DSP,
                content = "name: Searchable",
            ),
        )

        client().publish(pkg)

        val results = client().search(SearchQuery())
        assertTrue(results.any { it.id == "searchable-workflow" })
    }
}
