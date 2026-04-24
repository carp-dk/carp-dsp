package carp.dsp.core.application.registry

import health.workflows.interfaces.api.CompatibilityEvaluator
import health.workflows.interfaces.api.CompatibilityReport
import health.workflows.interfaces.api.ConsumptionInterface
import health.workflows.interfaces.api.DefaultCompatibilityEvaluator
import health.workflows.interfaces.api.LineageGraph
import health.workflows.interfaces.api.PlatformProfile
import health.workflows.interfaces.api.PublishResult
import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.ComponentRef
import health.workflows.interfaces.model.WorkflowArtifactPackage

/**
 * DSP implementation of [ConsumptionInterface].
 *
 * Delegates [getComponent], [search], and [publish] to [registry].
 * [checkCompatibility] is evaluated locally via [evaluator] — no extra round-trip needed.
 * [resolveDependencies] is derived from the package's dependency list.
 * [getDOI] and [getLineage] are stubbed for R1.
 */
class DspConsumptionService(
    private val registry: RegistryPort,
    private val evaluator: CompatibilityEvaluator = DefaultCompatibilityEvaluator,
) : ConsumptionInterface {

    override suspend fun getComponent(id: String, version: String): WorkflowArtifactPackage =
        registry.getComponent(id, version)

    override suspend fun search(query: SearchQuery): List<WorkflowArtifactPackage> =
        registry.search(query)

    override suspend fun publish(pkg: WorkflowArtifactPackage): PublishResult =
        registry.publish(pkg)

    override suspend fun checkCompatibility(
        id: String,
        version: String,
        profile: PlatformProfile,
    ): CompatibilityReport {
        val pkg = getComponent(id, version)
        return evaluator.evaluate(pkg, profile)
    }

    override suspend fun resolveDependencies(id: String, version: String): List<ComponentRef> {
        val pkg = getComponent(id, version)
        return pkg.dependencies ?: emptyList()
    }

    override suspend fun getDOI(id: String, version: String): String =
        throw NotImplementedError("DOI minting not wired in R1")

    override suspend fun getLineage(id: String, version: String): LineageGraph =
        LineageGraph()
}
