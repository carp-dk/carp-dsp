package carp.dsp.core.infrastructure.process

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.ExecutionOutput
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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

    override suspend fun execute(
        process: PhysioNetRetrievalProcess,
        outputPath: String
    ): List<ExecutionOutput> {

        val outputs = mutableListOf<ExecutionOutput>()

        println("Starting PhysioNet data retrieval...")
        println("  Dataset: ${process.datasetId}")
        println("  Version: ${process.version}")
        println("  Files to download: ${process.files.size}")
        println()

        // Download each file
        for ((index, fileName) in process.files.withIndex()) {
            println("Downloading file ${index + 1}/${process.files.size}: $fileName")

            try {
                val output = downloadFile(process, fileName, outputPath)
                outputs.add(output)

                if (output.success) {
                    println("  ✅ Success - ${output.statistics.byteSize?.let { "${it / 1024} KB" } ?: "unknown size"}")
                } else {
                    println("  ❌ Failed - ${output.errorMessage}")
                }
            } catch (e: Exception) {
                println("  ❌ Error - ${e.message}")
                outputs.add(createFailureOutput(
                    fileName,
                    "Unexpected error: ${e.message}"
                ))
            }
        }

        // Summary
        val successful = outputs.count { it.success }
        val failed = outputs.count { !it.success }
        println()
        println("Download complete: $successful successful, $failed failed")

        return outputs
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
                    val file = java.io.File(outputFile)
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
                    throw Exception("HTTP ${response.status.value}: ${response.status.description}")
                }

            } catch (e: Exception) {
                lastException = e
                attempt++

                if (attempt <= maxRetries) {
                    // Exponential backoff: 1s, 2s, 4s, 8s...
                    val backoffMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(10000L)
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

