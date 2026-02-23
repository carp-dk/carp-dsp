package carp.dsp.core.infrastructure.process

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.FileFormat
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhysioNetRetrievalExecutorTest {

    private var tempDir: File? = null
    private var httpClient: HttpClient? = null
    private lateinit var executor: PhysioNetRetrievalExecutor

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("physionet-test").toFile()
        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 3000
            }
        }
        executor = PhysioNetRetrievalExecutor(httpClient!!)
    }

    @AfterTest
    fun cleanup() {
        httpClient?.close()
        tempDir?.deleteRecursively()
    }

    @Test
    fun `constructor creates executor with HttpClient`() {
        val client = HttpClient(CIO)
        val testExecutor = PhysioNetRetrievalExecutor(client)

        assertNotNull(testExecutor)
        client.close()
    }

    @Test
    fun `PhysioNet process has correct properties`() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = listOf("PATIENTS.csv", "ADMISSIONS.csv"),
            expectedFormat = FileFormat.CSV,
            authentication = Authentication.Basic("user", "pass"),
            baseUrl = "https://custom.physionet.org/files"
        )

        assertEquals("mimic-iii-demo", process.datasetId)
        assertEquals("1.4", process.version)
        assertEquals(listOf("PATIENTS.csv", "ADMISSIONS.csv"), process.files)
        assertEquals(FileFormat.CSV, process.expectedFormat)
        assertEquals("https://custom.physionet.org/files", process.baseUrl)
        assertTrue(process.requiresAuthentication)
        assertEquals("PhysioNet Data Retrieval", process.name)
    }

    @Test
    fun `PhysioNet process generates correct URLs`() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.0",
            baseUrl = "https://physionet.org/files"
        )

        val fileUrl = process.getFileUrl("data.csv")
        val metadataUrl = process.getMetadataUrl()

        assertEquals("https://physionet.org/files/test-dataset/1.0/data.csv", fileUrl)
        assertEquals("https://physionet.org/files/test-dataset/1.0/SHA256SUMS.txt", metadataUrl)
    }

    @Test
    fun `PhysioNet process validates dataset IDs`() {
        val validProcess = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val invalidProcess = PhysioNetRetrievalProcess(
            datasetId = "INVALID_ID",
            version = "1.0"
        )

        assertTrue(validProcess.validateDatasetId())
        assertFalse(invalidProcess.validateDatasetId())
    }

    @Test
    fun `PhysioNet process estimates download sizes for known datasets`() {
        val mimicProcess = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val ptbProcess = PhysioNetRetrievalProcess(
            datasetId = "ptb-xl",
            version = "1.0"
        )

        val unknownProcess = PhysioNetRetrievalProcess(
            datasetId = "unknown-dataset",
            version = "1.0"
        )

        val mimicSize = mimicProcess.estimateDownloadSize()
        val ptbSize = ptbProcess.estimateDownloadSize()
        val unknownSize = unknownProcess.estimateDownloadSize()

        assertNotNull(mimicSize)
        assertTrue(mimicSize > 0)
        assertNotNull(ptbSize)
        assertTrue(ptbSize > mimicSize) // PTB-XL is larger
        assertEquals(null, unknownSize)
    }

    @Test
    fun `PhysioNet process with different authentication types`() {
        val basicAuthProcess = PhysioNetRetrievalProcess(
            datasetId = "dataset1",
            version = "1.0",
            authentication = Authentication.Basic("user", "pass")
        )

        val bearerAuthProcess = PhysioNetRetrievalProcess(
            datasetId = "dataset2",
            version = "1.0",
            authentication = Authentication.Bearer("token123")
        )

        val apiKeyAuthProcess = PhysioNetRetrievalProcess(
            datasetId = "dataset3",
            version = "1.0",
            authentication = Authentication.ApiKey("key123", "X-API-Key")
        )

        val noAuthProcess = PhysioNetRetrievalProcess(
            datasetId = "dataset4",
            version = "1.0",
            authentication = null
        )

        assertTrue(basicAuthProcess.requiresAuthentication)
        assertTrue(bearerAuthProcess.requiresAuthentication)
        assertTrue(apiKeyAuthProcess.requiresAuthentication)
        assertFalse(noAuthProcess.requiresAuthentication)
    }

    @Test
    fun `PhysioNet process with custom retrieval config`() {
        val customConfig = RetrievalConfig(
            maxRetries = 5,
            timeoutMs = 60000,
            useCache = false,
            cacheDir = "/custom/cache",
            customParams = mapOf("param1" to "value1")
        )

        val process = PhysioNetRetrievalProcess(
            datasetId = "custom-dataset",
            version = "2.0",
            files = listOf("custom.csv"),
            retrievalConfig = customConfig
        )

        assertEquals(customConfig, process.retrievalConfig)
        assertEquals(5, process.retrievalConfig.maxRetries)
        assertEquals(60000, process.retrievalConfig.timeoutMs)
        assertFalse(process.retrievalConfig.useCache)
        assertEquals("/custom/cache", process.retrievalConfig.cacheDir)
        assertEquals(mapOf("param1" to "value1"), process.retrievalConfig.customParams)
    }

    @Test
    fun `PhysioNet process with empty file list`() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "empty-dataset",
            version = "1.0",
            files = emptyList()
        )

        assertTrue(process.files.isEmpty())
        assertEquals(emptyList(), process.files)
    }

    @Test
    fun `PhysioNet process with different file formats`() {
        val csvProcess = PhysioNetRetrievalProcess(
            datasetId = "csv-data",
            version = "1.0",
            expectedFormat = FileFormat.CSV
        )

        val jsonProcess = PhysioNetRetrievalProcess(
            datasetId = "json-data",
            version = "1.0",
            expectedFormat = FileFormat.JSON
        )

        val binaryProcess = PhysioNetRetrievalProcess(
            datasetId = "binary-data",
            version = "1.0",
            expectedFormat = FileFormat.BINARY
        )

        assertEquals(FileFormat.CSV, csvProcess.expectedFormat)
        assertEquals(FileFormat.JSON, jsonProcess.expectedFormat)
        assertEquals(FileFormat.BINARY, binaryProcess.expectedFormat)
    }

    @Test
    fun `executor inherits from DataRetrievalExecutor`() {
        // Test inheritance through class check
        assertTrue(DataRetrievalExecutor::class.java.isAssignableFrom(executor::class.java))
    }

    @Test
    fun `printFileResult correctly calculates KB from bytes`() {
        // Test the KB calculation behavior (which uses BYTES_TO_KB_DIVISOR internally)
        val successOutput = createTestSuccessOutput("test.csv", "/tmp/test.csv", 2048L)

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printFileResult",
                dk.cachet.carp.analytics.domain.data.ExecutionOutput::class.java
            )
            method.isAccessible = true
            method.invoke(executor, successOutput)

            val output = capturedOutput.toString()
            assertTrue(output.contains("✅ Success"))
            // 2048 bytes / 1024 = 2 KB (testing the division behavior)
            assertTrue(output.contains("2 KB"))
        } finally {
            System.setOut(originalOut)
        }

        // Test with different byte size to verify calculation
        val largerOutput = createTestSuccessOutput("large.csv", "/tmp/large.csv", 5120L)
        val capturedOutput2 = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput2))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printFileResult",
                dk.cachet.carp.analytics.domain.data.ExecutionOutput::class.java
            )
            method.isAccessible = true
            method.invoke(executor, largerOutput)

            val output2 = capturedOutput2.toString()
            // 5120 bytes / 1024 = 5 KB (verifies the divisor is 1024)
            assertTrue(output2.contains("5 KB"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printRetrievalHeader displays correct information`() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.5",
            files = listOf("file1.csv", "file2.csv", "file3.csv")
        )

        // Capture stdout by redirecting System.out
        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            // Use reflection to call private method
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printRetrievalHeader",
                PhysioNetRetrievalProcess::class.java
            )
            method.isAccessible = true
            method.invoke(executor, process)

            val output = capturedOutput.toString()
            assertTrue(output.contains("Starting PhysioNet data retrieval..."))
            assertTrue(output.contains("Dataset: test-dataset"))
            assertTrue(output.contains("Version: 1.5"))
            assertTrue(output.contains("Files to download: 3"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printFileResult displays success with file size`() {
        val successOutput = createTestSuccessOutput("test.csv", "/tmp/test.csv", 2048L)

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printFileResult",
                dk.cachet.carp.analytics.domain.data.ExecutionOutput::class.java
            )
            method.isAccessible = true
            method.invoke(executor, successOutput)

            val output = capturedOutput.toString()
            assertTrue(output.contains("✅ Success"))
            assertTrue(output.contains("2 KB")) // 2048 / 1024 = 2 KB
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printFileResult displays success without file size`() {
        val successOutput = createTestSuccessOutput("test.csv", "/tmp/test.csv", null)

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printFileResult",
                dk.cachet.carp.analytics.domain.data.ExecutionOutput::class.java
            )
            method.isAccessible = true
            method.invoke(executor, successOutput)

            val output = capturedOutput.toString()
            assertTrue(output.contains("✅ Success"))
            assertTrue(output.contains("unknown size"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printFileResult displays failure message`() {
        val failureOutput = createTestFailureOutput("failed.csv", "Network timeout")

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printFileResult",
                dk.cachet.carp.analytics.domain.data.ExecutionOutput::class.java
            )
            method.isAccessible = true
            method.invoke(executor, failureOutput)

            val output = capturedOutput.toString()
            assertTrue(output.contains("❌ Failed"))
            assertTrue(output.contains("Network timeout"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printRetrievalSummary displays correct counts`() {
        val outputs = listOf(
            createTestSuccessOutput("success1.csv", "/tmp/success1.csv", 1024L),
            createTestSuccessOutput("success2.csv", "/tmp/success2.csv", 2048L),
            createTestFailureOutput("failure1.csv", "Error 1"),
            createTestFailureOutput("failure2.csv", "Error 2"),
            createTestFailureOutput("failure3.csv", "Error 3")
        )

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printRetrievalSummary",
                List::class.java
            )
            method.isAccessible = true
            method.invoke(executor, outputs)

            val output = capturedOutput.toString()
            assertTrue(output.contains("Download complete: 2 successful, 3 failed"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printRetrievalSummary handles all success case`() {
        val outputs = listOf(
            createTestSuccessOutput("success1.csv", "/tmp/success1.csv", 1024L),
            createTestSuccessOutput("success2.csv", "/tmp/success2.csv", 2048L)
        )

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printRetrievalSummary",
                List::class.java
            )
            method.isAccessible = true
            method.invoke(executor, outputs)

            val output = capturedOutput.toString()
            assertTrue(output.contains("Download complete: 2 successful, 0 failed"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `printRetrievalSummary handles all failure case`() {
        val outputs = listOf(
            createTestFailureOutput("failure1.csv", "Error 1"),
            createTestFailureOutput("failure2.csv", "Error 2")
        )

        val originalOut = System.out
        val capturedOutput = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capturedOutput))

        try {
            val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
                "printRetrievalSummary",
                List::class.java
            )
            method.isAccessible = true
            method.invoke(executor, outputs)

            val output = capturedOutput.toString()
            assertTrue(output.contains("Download complete: 0 successful, 2 failed"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `addAuthentication method exists and is accessible`() {
        // Verify the addAuthentication method exists and can be accessed via reflection
        val method = PhysioNetRetrievalExecutor::class.java.getDeclaredMethod(
            "addAuthentication",
            io.ktor.client.request.HttpRequestBuilder::class.java,
            Authentication::class.java
        )
        assertNotNull(method)
        method.isAccessible = true

        // Test that method can be invoked without throwing (even if we can't test the result easily)
        val requestBuilder = io.ktor.client.request.HttpRequestBuilder()
        requestBuilder.url {
            protocol = io.ktor.http.URLProtocol.HTTP
            host = "test.com"
        }

        // Test all authentication types don't throw exceptions
        method.invoke(executor, requestBuilder, Authentication.Basic("user", "pass"))
        method.invoke(executor, requestBuilder, Authentication.Bearer("token"))
        method.invoke(executor, requestBuilder, Authentication.ApiKey("key"))
        method.invoke(executor, requestBuilder, Authentication.OAuth2("token"))
        method.invoke(executor, requestBuilder, null)
    }

    // Helper methods

    private fun createTestSuccessOutput(outputId: String, filePath: String, fileSize: Long?): dk.cachet.carp.analytics.domain.data.ExecutionOutput {
        return dk.cachet.carp.analytics.domain.data.ExecutionOutput(
            outputId = outputId,
            actualLocation = dk.cachet.carp.analytics.domain.data.FileSystemSource(filePath, FileFormat.CSV),
            statistics = dk.cachet.carp.analytics.domain.data.DataStatistics(byteSize = fileSize),
            timestamp = kotlinx.datetime.Clock.System.now(),
            success = true,
            errorMessage = null
        )
    }

    private fun createTestFailureOutput(outputId: String, errorMessage: String): dk.cachet.carp.analytics.domain.data.ExecutionOutput {
        return dk.cachet.carp.analytics.domain.data.ExecutionOutput(
            outputId = outputId,
            actualLocation = dk.cachet.carp.analytics.domain.data.FileSystemSource("", FileFormat.BINARY),
            statistics = dk.cachet.carp.analytics.domain.data.DataStatistics(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            success = false,
            errorMessage = errorMessage
        )
    }
}
