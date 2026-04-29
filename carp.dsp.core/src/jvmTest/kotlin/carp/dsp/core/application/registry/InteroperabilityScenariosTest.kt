package carp.dsp.core.application.registry

import health.workflows.interfaces.api.CompatibilitySignal
import health.workflows.interfaces.api.PlatformConstraints
import health.workflows.interfaces.api.PlatformProfile
import health.workflows.interfaces.api.PublishResult
import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.AdaptationSeverity
import health.workflows.interfaces.model.EnvironmentType
import health.workflows.interfaces.model.NativeWorkflowAsset
import health.workflows.interfaces.model.PackageMetadata
import health.workflows.interfaces.model.ScriptLanguage
import health.workflows.interfaces.model.WorkflowArtifactPackage
import health.workflows.interfaces.model.WorkflowFormat
import health.workflows.interfaces.model.WorkflowGranularity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Representative profile for the Aware/RAPIDS platform, defined here as a test fixture.
 *
 * This is DSP's assumed view of the Aware platform capabilities for interop testing only.
 * The authoritative profile is owned and maintained by the Aware team.
 */
private val awareRapidsTestProfile = PlatformProfile(
    platformId = "aware-rapids",
    supportedFormats = listOf(WorkflowFormat.RAPIDS, WorkflowFormat.CWL, WorkflowFormat.CARP_DSP),
    supportedEnvironments = listOf(EnvironmentType.DOCKER, EnvironmentType.SYSTEM),
    environmentsRequiringAdaptation = listOf(EnvironmentType.CONDA, EnvironmentType.PIXI),
    supportedOperations = listOf("getComponent", "search", "publish", "checkCompatibility"),
    constraints = PlatformConstraints(
        maxDependencyDepth = 3,
        requiresDOI = false,
        supportedScriptLanguages = listOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH, ScriptLanguage.SHELL),
    ),
)

/**
 * Interoperability scenarios demonstrating that CARP-DSP and Aware/RAPIDS
 * can exchange workflow packages through the shared HWF interface.
 *
 * These tests use an in-memory [StubRegistry] — no live server required.
 * For full end-to-end tests against a running HWF server, see RegistryClientIntegrationTest.
 *
 * Scenarios:
 *   1. Publish & Fetch  — DSP publishes a package and fetches it back unchanged.
 *   2. Compatibility    — Aware checks a CONDA-env package against the RAPIDS profile;
 *                         expects COMPATIBLE_WITH_ADAPTATIONS (CONDA needs adaptation).
 *   3. Cross-platform search — Aware searches the DSP index by tag; result includes the package.
 */
class InteroperabilityScenariosTest {

    // ── shared fixture ───────────────────────────────────────────────────────

    private val condaPackage = WorkflowArtifactPackage(
        id = "hr-activity",
        version = "1.0.0",
        contentHash = "sha256:abc123",
        metadata = PackageMetadata(
            name = "HR Activity",
            granularity = WorkflowGranularity.WORKFLOW,
            tags = listOf("eeg", "preprocessing", "hr-activity"),
        ),
        native = NativeWorkflowAsset(
            format = WorkflowFormat.CARP_DSP,
            content = """
                name: hr-activity
                environments:
                  hr-env:
                    name: "hr-env"
                    kind: "conda"
                    spec: {}
            """.trimIndent(),
        ),
    )

    // ── Scenario 1: Publish & Fetch ──────────────────────────────────────────

    /**
     * DSP publishes a workflow package and fetches it back.
     * Verifies that id, version, and contentHash are preserved through the registry round-trip.
     */
    @Test
    fun `scenario 1 - DSP publishes a package and fetches it back unchanged`() = runTest {
        val registry = InMemoryStubRegistry()
        val service = DspConsumptionService(registry = registry)

        val publishResult = service.publish(condaPackage)
        assertTrue(publishResult.accepted)

        val fetched = service.getComponent(condaPackage.id, condaPackage.version)
        assertEquals(condaPackage.id, fetched.id)
        assertEquals(condaPackage.version, fetched.version)
        assertEquals(condaPackage.contentHash, fetched.contentHash)
        assertEquals(condaPackage.metadata.name, fetched.metadata.name)
    }

    // ── Scenario 2: Compatibility Check ─────────────────────────────────────

    /**
     * Aware calls checkCompatibility on a CONDA-environment DSP package
     * against the RAPIDS platform profile.
     *
     * Expected: COMPATIBLE_WITH_ADAPTATIONS — CONDA is not natively supported by RAPIDS
     * but can be adapted. The report should carry a WARNING hint for the CONDA environment.
     */
    @Test
    fun `scenario 2 - CONDA package on RAPIDS profile produces COMPATIBLE_WITH_ADAPTATIONS`() = runTest {
        val registry = InMemoryStubRegistry()
        val service = DspConsumptionService(registry = registry)
        service.publish(condaPackage)

        val report = service.checkCompatibility(condaPackage.id, condaPackage.version, awareRapidsTestProfile)

        assertNotNull(report)
        assertEquals("aware-rapids", report.platformId)
        assertEquals(CompatibilitySignal.COMPATIBLE_WITH_ADAPTATIONS, report.signal)
        assertTrue(report.compatible)

        val condaHint = report.requiredAdaptations.single { "environments[CONDA]" == it.field }
        assertEquals(AdaptationSeverity.WARNING, condaHint.severity)
        assertTrue(condaHint.message.contains("CONDA", ignoreCase = true))

        assertEquals(listOf(EnvironmentType.CONDA), report.missingEnvironments)
    }

    /**
     * A DOCKER-environment package on the RAPIDS profile should be fully compatible —
     * RAPIDS runs Docker containers natively.
     */
    @Test
    fun `scenario 2b - DOCKER package on RAPIDS profile produces COMPATIBLE`() = runTest {
        val dockerPackage = condaPackage.copy(
            id = "hr-activity-docker",
            native = condaPackage.native.copy(
                content = condaPackage.native.content.replace("conda", "docker"),
            ),
        )
        val registry = InMemoryStubRegistry()
        val service = DspConsumptionService(registry = registry)
        service.publish(dockerPackage)

        val report = service.checkCompatibility(dockerPackage.id, dockerPackage.version, awareRapidsTestProfile)

        assertEquals(CompatibilitySignal.COMPATIBLE, report.signal)
        assertTrue(report.requiredAdaptations.isEmpty())
    }

    // ── Scenario 3: Cross-platform search ───────────────────────────────────

    /**
     * Aware calls search on the DSP index using a keyword that matches the published package.
     * Verifies the result list contains the published package.
     */
    @Test
    fun `scenario 3 - Aware searches DSP index by tag and finds the published package`() = runTest {
        val registry = InMemoryStubRegistry()
        val service = DspConsumptionService(registry = registry)
        service.publish(condaPackage)

        val results = service.search(SearchQuery(keywords = listOf("eeg")))

        assertTrue(results.isNotEmpty(), "Search should return at least one result")
        val match = results.find { it.id == condaPackage.id }
        assertNotNull(match, "Published package should appear in search results for 'eeg'")
        assertEquals(condaPackage.version, match.version)
    }

    @Test
    fun `scenario 3b - search with non-matching keyword returns empty list`() = runTest {
        val registry = InMemoryStubRegistry()
        val service = DspConsumptionService(registry = registry)
        service.publish(condaPackage)

        val results = service.search(SearchQuery(keywords = listOf("mri-unrelated")))

        assertTrue(results.isEmpty(), "No results expected for unrelated keyword")
    }
}

// ── In-memory registry ───────────────────────────────────────────────────────

/**
 * Minimal in-memory [RegistryPort] for interoperability tests.
 * Search matches packages whose name or tags contain any of the query keywords.
 */
private class InMemoryStubRegistry : RegistryPort {
    private val store = mutableMapOf<Pair<String, String>, WorkflowArtifactPackage>()

    override suspend fun publish(pkg: WorkflowArtifactPackage): PublishResult {
        store[pkg.id to pkg.version] = pkg
        return PublishResult(accepted = true, id = pkg.id, version = pkg.version)
    }

    override suspend fun getComponent(id: String, version: String): WorkflowArtifactPackage =
        store[id to version] ?: error("Package $id:$version not found")

    override suspend fun search(query: SearchQuery): List<WorkflowArtifactPackage> {
        if (query.keywords.isEmpty() && query.tags.isEmpty()) return store.values.toList()
        val terms = (query.keywords + query.tags).map { it.lowercase() }
        return store.values.filter { pkg ->
            val searchable = buildList {
                add(pkg.metadata.name.lowercase())
                addAll(pkg.metadata.tags.orEmpty().map { it.lowercase() })
            }
            terms.any { term -> searchable.any { it.contains(term) } }
        }
    }
}
