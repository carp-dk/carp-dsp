package carp.dsp.core.infrastructure.process

import carp.dsp.core.domain.process.DataRetrievalProcess
import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

/**
 * Factory for creating data retrieval executors.
 * This allows the strategy to get the appropriate executor for each retrieval process type.
 */
class DataRetrievalExecutorFactory {

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            // Configure client settings
            expectSuccess = false // Don't throw on non-2xx responses

            // Install timeout plugin
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000 // 2 minutes default
                connectTimeoutMillis = 30_000  // 30 seconds to connect
                socketTimeoutMillis = 120_000  // 2 minutes socket timeout
            }
        }
    }

    /**
     * Gets the appropriate executor for the given retrieval process.
     */
    fun <T : DataRetrievalProcess> getExecutor(process: T): DataRetrievalExecutor<T> {
        @Suppress("UNCHECKED_CAST")
        return when (process) {
            is PhysioNetRetrievalProcess -> PhysioNetRetrievalExecutor(httpClient) as DataRetrievalExecutor<T>
            else -> throw IllegalArgumentException("No executor available for ${process::class.simpleName}")
        }
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    fun close() {
        httpClient.close()
    }
}

