package carp.dsp.core.domain.process

import dk.cachet.carp.analytics.domain.process.WorkflowProcess

/**
 * Base interface for data retrieval processes.
 *
 * Data retrieval processes fetch data from external sources (URLs, repositories, APIs)
 * and make it available for workflow steps. Unlike AnalysisProcess which transforms
 * data, or ExternalProcess which executes external tools, DataRetrievalProcess is
 * specifically designed for obtaining data from various sources.
 */
interface DataRetrievalProcess : WorkflowProcess {
    /**
     * Configuration for the retrieval operation.
     * This can include authentication, caching preferences, etc.
     */
    val retrievalConfig: RetrievalConfig

    /**
     * Whether this retrieval process supports caching.
     * If true, the executor may cache downloaded data to avoid redundant downloads.
     */
    val supportsCaching: Boolean get() = true

    /**
     * Whether this retrieval requires authentication.
     */
    val requiresAuthentication: Boolean get() = false
}

/**
 * Configuration for data retrieval operations.
 */
data class RetrievalConfig(
    /**
     * Maximum number of retry attempts on failure.
     */
    val maxRetries: Int = 3,

    /**
     * Timeout in milliseconds for the retrieval operation.
     */
    val timeoutMs: Long = 30_000,

    /**
     * Whether to use cached data if available.
     */
    val useCache: Boolean = true,

    /**
     * Cache directory path (null = use system default).
     */
    val cacheDir: String? = null,

    /**
     * Additional custom configuration parameters.
     */
    val customParams: Map<String, String> = emptyMap()
)

