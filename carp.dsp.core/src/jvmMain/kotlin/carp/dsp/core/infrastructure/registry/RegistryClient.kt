package carp.dsp.core.infrastructure.registry

import carp.dsp.core.application.registry.RegistryPort
import health.workflows.interfaces.api.PublishResult
import health.workflows.interfaces.api.SearchQuery
import health.workflows.interfaces.model.WorkflowArtifactPackage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * HTTP client for the health-workflow-interfaces registry server.
 *
 * Talks to the HWF server REST API — not to WorkflowHub directly.
 * WorkflowHub integration is handled server-side via [WorkflowHubPort].
 *
 * [http] should be configured with JSON content negotiation:
 * ```
 * HttpClient(CIO) { install(ContentNegotiation) { json() } }
 * ```
 */
class RegistryClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : RegistryPort {

    override suspend fun getComponent(id: String, version: String): WorkflowArtifactPackage =
        http.get("$baseUrl/api/v1/components/$id/$version") {
            header("Authorization", "Bearer $apiKey")
        }.body()

    override suspend fun search(query: SearchQuery): List<WorkflowArtifactPackage> =
        http.post("$baseUrl/api/v1/components/search") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(query)
        }.body()

    override suspend fun publish(pkg: WorkflowArtifactPackage): PublishResult =
        http.post("$baseUrl/api/v1/components") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(pkg)
        }.body()
}
