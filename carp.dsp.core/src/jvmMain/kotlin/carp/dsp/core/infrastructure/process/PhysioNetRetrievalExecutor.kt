package carp.dsp.core.infrastructure.process

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.ExecutionOutput
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.delay
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Executor for PhysioNet data retrieval processes.
 *
 * This executor implements the specific logic for downloading data from PhysioNet:
 * - HTTP(S) file downloads using Ktor client
 * - Basic/Bearer authentication for restricted datasets
 * - Retry logic with exponential backoff
 * - File size tracking
 * - Success/failure status reporting
 */
class PhysioNetRetrievalExecutor(
    private val httpClient: HttpClient
) : DataRetrievalExecutor<PhysioNetRetrievalProcess>() {

    companion object {
        private const val BYTES_TO_KB_DIVISOR = 1024
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 10_000L
    }

    override suspend fun execute(
        process: PhysioNetRetrievalProcess,
        outputPath: String
    ): List<ExecutionOutput> {
        printRetrievalHeader(process)

        val outputs = process.files.mapIndexed { index, fileName ->
            processFileDownload(process, fileName, outputPath, index + 1, process.files.size)
        }.toMutableList()

        printRetrievalSummary(outputs)
        return outputs
    }

    /**
     * Prints the retrieval header information.
     */
    private fun printRetrievalHeader(process: PhysioNetRetrievalProcess) {
        println("Starting PhysioNet data retrieval...")
        println("  Dataset: ${process.datasetId}")
        println("  Version: ${process.version}")
        println("  Files to download: ${process.files.size}")
        println()
    }

    /**
     * Processes a single file download with error handling.
     */
    private suspend fun processFileDownload(
        process: PhysioNetRetrievalProcess,
        fileName: String,
        outputPath: String,
        fileNumber: Int,
        totalFiles: Int
    ): ExecutionOutput {
        println("Downloading file $fileNumber/$totalFiles: $fileName")

        return try {
            val output = downloadFile(process, fileName, outputPath)
            printFileResult(output)
            output
        } catch (e: IOException) {
            println("  ❌ Error - ${e.message}")
            createFailureOutput(fileName, "IO error: ${e.message}")
        }
    }

    /**
     * Prints the result of a single file download.
     */
    private fun printFileResult(output: ExecutionOutput) {
        if (output.success) {
            val sizeText = output.statistics.byteSize?.let {
                "${it / BYTES_TO_KB_DIVISOR} KB"
            } ?: "unknown size"
            println("  ✅ Success - $sizeText")
        } else {
            println("  ❌ Failed - ${output.errorMessage}")
        }
    }

    /**
     * Prints the retrieval summary.
     */
    private fun printRetrievalSummary(outputs: List<ExecutionOutput>) {
        val successful = outputs.count { it.success }
        val failed = outputs.count { !it.success }
        println()
        println("Download complete: $successful successful, $failed failed")
    }

    /**
     * Download a single file from PhysioNet with retry logic.
     */
    private suspend fun downloadFile(
        process: PhysioNetRetrievalProcess,
        fileName: String,
        outputPath: String
    ): ExecutionOutput {
        val url = process.getFileUrl(fileName)
        val outputFile = "$outputPath/$fileName"

        // Check cache first
        val cachedFile = process.retrievalConfig.cacheDir?.let { cacheDir ->
            checkCache(process, fileName, cacheDir)
        }

        if (cachedFile != null) {
            println("  📦 Using cached file")
            return createSuccessOutput(
                outputId = fileName,
                filePath = cachedFile,
                format = process.expectedFormat,
                fileSize = null
            )
        }

        // Download with retries
        var lastException: Exception? = null
        var attempt = 0
        val maxRetries = process.retrievalConfig.maxRetries

        while (attempt <= maxRetries) {
            try {
                val response = httpClient.get(url) {
                    // Add authentication if provided
                    addAuthentication(process.authentication)
                }

                if (response.status.isSuccess()) {
                    val fileSize = response.contentLength()
                    val fileContent = response.readBytes()

                    // Write file to disk
                    val file = File(outputFile)
                    file.parentFile?.mkdirs() // Create directories if needed
                    file.writeBytes(fileContent)

                    println("  📥 Downloaded ${fileContent.size} bytes")
                    println("  💾 Saved to: ${file.absolutePath}")

                    return createSuccessOutput(
                        outputId = fileName,
                        filePath = file.absolutePath,
                        format = process.expectedFormat,
                        fileSize = fileSize
                    )
                } else {
                    error("HTTP ${response.status.value}: ${response.status.description}")
                }
            } catch (e: IOException) {
                lastException = e
                attempt++

                if (attempt <= maxRetries) {
                    // Exponential backoff: 1s, 2s, 4s, 8s...
                    val backoffMs = (INITIAL_BACKOFF_MS * (1 shl (attempt - 1))).coerceAtMost(MAX_BACKOFF_MS)
                    println("  ⚠️  Retry $attempt/$maxRetries after ${backoffMs}ms...")
                    delay(backoffMs)
                }
            }
        }

        // All retries failed
        return createFailureOutput(
            fileName,
            "Failed after $maxRetries retries: ${lastException?.message}"
        )
    }

    /**
     * Add authentication headers to the HTTP request.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun HttpRequestBuilder.addAuthentication(auth: Authentication?) {
        when (auth) {
            is Authentication.Basic -> {
                val credentials = "${auth.username}:${auth.password}"
                val encoded = Base64.encode(credentials.encodeToByteArray())
                headers {
                    append(HttpHeaders.Authorization, "Basic $encoded")
                }
            }
            is Authentication.Bearer -> {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${auth.token}")
                }
            }
            is Authentication.ApiKey -> {
                headers {
                    append(auth.headerName, auth.key)
                }
            }
            else -> {
                // No authentication
            }
        }
    }
}
