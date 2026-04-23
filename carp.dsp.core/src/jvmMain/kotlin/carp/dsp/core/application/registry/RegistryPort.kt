package carp.dsp.core.application.registry

import health.workflows.interfaces.api.PublishResult
import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.WorkflowArtifactPackage

/**
 * Port for interacting with the HWF registry server (health-workflow-interfaces).
 *
 * carp-dsp never calls WorkflowHub directly — the HWF server is the gateway.
 *
 * Swap implementations:
 * - [carp.dsp.core.infrastructure.registry.RegistryClient] — HTTP client for a live server
 * - A test stub — for unit tests without a running server
 */
interface RegistryPort {
    suspend fun getComponent(id: String, version: String): WorkflowArtifactPackage
    suspend fun search(query: SearchQuery): List<WorkflowArtifactPackage>
    suspend fun publish(pkg: WorkflowArtifactPackage): PublishResult
}
