package carp.dsp.core.infrastructure.process

import carp.dsp.core.domain.process.DataRetrievalProcess
import dk.cachet.carp.analytics.domain.data.DataStatistics
import dk.cachet.carp.analytics.domain.data.ExecutionOutput
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import kotlinx.datetime.Clock

/**
 * Base executor for data retrieval processes.
 *
 * This executor handles the common logic for retrieving data from external sources:
 * - Cache checking
 * - Download with retries
 * - Progress tracking
 * - Validation
 * - Result packaging
 */
abstract class DataRetrievalExecutor<TProcess : DataRetrievalProcess> {

    /**
     * Execute the data retrieval process.
     *
     * @param process The retrieval process configuration
     * @param outputPath Base directory where retrieved files should be stored
     * @return List of ExecutionOutput representing the retrieved files
     */
    abstract suspend fun execute(process: TProcess, outputPath: String): List<ExecutionOutput>

    /**
     * Check if data is available in cache.
     */
    protected fun checkCache(process: TProcess, fileName: String, cacheDir: String): String? {
        if (!process.retrievalConfig.useCache) return null

        // Check if file exists and is valid
        // Implementation depends on platform (JVM vs JS)
        return null // TODO: Implement platform-specific cache check
    }

    /**
     * Create an ExecutionOutput for a successfully retrieved file.
     */
    protected fun createSuccessOutput(
        outputId: String,
        filePath: String,
        format: FileFormat,
        fileSize: Long? = null
    ): ExecutionOutput {
        return ExecutionOutput(
            outputId = outputId,
            actualLocation = FileSystemSource(
                path = filePath,
                format = format
            ),
            statistics = DataStatistics(
                byteSize = fileSize
            ),
            timestamp = Clock.System.now(),
            success = true,
            errorMessage = null
        )
    }

    /**
     * Create an ExecutionOutput for a failed retrieval.
     */
    protected fun createFailureOutput(
        outputId: String,
        errorMessage: String
    ): ExecutionOutput {
        return ExecutionOutput(
            outputId = outputId,
            actualLocation = FileSystemSource(
                path = "",
                format = FileFormat.BINARY
            ),
            statistics = DataStatistics(),
            timestamp = Clock.System.now(),
            success = false,
            errorMessage = errorMessage
        )
    }
}

