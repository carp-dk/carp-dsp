package carp.dsp.core.infrastructure.process

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.process.DataRetrievalProcess
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

/**
 * Factory for creating data retrieval executors.
 * This allows the strategy to get the appropriate executor for each retrieval process type.
 */
class DataRetrievalExecutorFactory {

    companion object {
        private const val REQUEST_TIMEOUT_MS = 120_000L // 2 minutes default
        private const val CONNECT_TIMEOUT_MS = 30_000L // 30 seconds to connect
        private const val SOCKET_TIMEOUT_MS = 120_000L // 2 minutes socket timeout
    }

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            // Configure client settings
            expectSuccess = false // Don't throw on non-2xx responses

            // Install timeout plugin
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
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

