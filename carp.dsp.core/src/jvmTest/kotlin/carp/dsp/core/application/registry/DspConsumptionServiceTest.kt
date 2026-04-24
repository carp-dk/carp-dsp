package carp.dsp.core.application.registry

import health.workflows.interfaces.api.CompatibilitySignal
import health.workflows.interfaces.api.PlatformConstraints
import health.workflows.interfaces.api.PlatformProfile
import health.workflows.interfaces.api.PublishResult
import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.ComponentRef
import health.workflows.interfaces.model.DataSensitivity
import health.workflows.interfaces.model.NativeWorkflowAsset
import health.workflows.interfaces.model.PackageMetadata
import health.workflows.interfaces.model.WorkflowArtifactPackage
import health.workflows.interfaces.model.WorkflowFormat
import health.workflows.interfaces.model.WorkflowGranularity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// -- Stub ---------------------------------------------------------------------

private class StubRegistry(
    private val fixture: WorkflowArtifactPackage,
) : RegistryPort {
    var publishedPkg: WorkflowArtifactPackage? = null
    var lastQuery: SearchQuery? = null

    override suspend fun getComponent(id: String, version: String) = fixture
    override suspend fun search(query: SearchQuery): List<WorkflowArtifactPackage> {
        lastQuery = query
        return listOf(fixture)
    }
    override suspend fun publish(pkg: WorkflowArtifactPackage): PublishResult {
        publishedPkg = pkg
        return PublishResult(accepted = true, id = pkg.id, version = pkg.version)
    }
}

// -- Fixtures ------------------------------------------------------------------

private val fixturePackage = WorkflowArtifactPackage(
    id = "risk-scoring",
    version = "1.0",
    contentHash = "abc123",
    metadata = PackageMetadata(
        name = "Risk Scoring",
        granularity = WorkflowGranularity.WORKFLOW,
        sensitivityClass = DataSensitivity.PUBLIC,
    ),
    native = NativeWorkflowAsset(
        format = WorkflowFormat.CARP_DSP,
        content = "name: Risk Scoring",
    ),
    dependencies = listOf(ComponentRef(id = "preprocess", version = "0.1")),
)

private val dspProfile = PlatformProfile(
    platformId = "carp-dsp",
    supportedFormats = listOf(WorkflowFormat.CARP_DSP),
    constraints = PlatformConstraints(maxDependencyDepth = 5, requiresDOI = false),
)

// -- Tests ---------------------------------------------------------------------

class DspConsumptionServiceTest {

    private val stub = StubRegistry(fixturePackage)
    private val service = DspConsumptionService(registry = stub)

    @Test
    fun `getComponent returns package from registry`() = runTest {
        val result = service.getComponent("risk-scoring", "1.0")
        assertEquals("risk-scoring", result.id)
        assertEquals("1.0", result.version)
    }

    @Test
    fun `search forwards query and returns results`() = runTest {
        val query = SearchQuery(keywords = listOf("risk"))
        val results = service.search(query)

        assertEquals(query, stub.lastQuery)
        assertEquals(1, results.size)
        assertEquals("risk-scoring", results.first().id)
    }

    @Test
    fun `publish forwards package and returns accepted result`() = runTest {
        val result = service.publish(fixturePackage)

        assertEquals(fixturePackage, stub.publishedPkg)
        assertTrue(result.accepted)
        assertEquals("risk-scoring", result.id)
    }

    @Test
    fun `checkCompatibility evaluates locally against fetched package`() = runTest {
        val report = service.checkCompatibility("risk-scoring", "1.0", dspProfile)

        assertNotNull(report)
        assertEquals("carp-dsp", report.platformId)
        // CARP_DSP format is supported → should be compatible or adapted, not blocked
        assertEquals(report.signal, CompatibilitySignal.COMPATIBLE)
    }

    @Test
    fun `resolveDependencies returns package dependency list`() = runTest {
        val deps = service.resolveDependencies("risk-scoring", "1.0")

        assertEquals(1, deps.size)
        assertEquals("preprocess", deps.first().id)
    }

    @Test
    fun `resolveDependencies returns empty list when package has no dependencies`() = runTest {
        val noDepsStub = StubRegistry(fixturePackage.copy(dependencies = null))
        val svc = DspConsumptionService(registry = noDepsStub)

        val deps = svc.resolveDependencies("risk-scoring", "1.0")
        assertTrue(deps.isEmpty())
    }

    @Test
    fun `getDOI throws NotImplementedError`() = runTest {
        assertFailsWith<NotImplementedError> {
            service.getDOI("risk-scoring", "1.0")
        }
    }

    @Test
    fun `getLineage returns empty graph`() = runTest {
        val graph = service.getLineage("risk-scoring", "1.0")
        assertTrue(graph.nodes.isEmpty())
    }
}
