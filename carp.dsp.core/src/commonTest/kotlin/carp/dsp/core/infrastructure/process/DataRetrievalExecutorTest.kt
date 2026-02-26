package carp.dsp.core.infrastructure.process

import carp.dsp.core.domain.process.DataRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.DataStatistics
import dk.cachet.carp.analytics.domain.data.ExecutionOutput
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataRetrievalExecutorTest {

    // Test implementation of DataRetrievalExecutor
    private class TestDataRetrievalExecutor : DataRetrievalExecutor<TestDataRetrievalProcess>() {
        var executeCalled = false
        var lastProcess: TestDataRetrievalProcess? = null
        var lastOutputPath: String? = null
        var mockOutputs: List<ExecutionOutput> = emptyList()

        override suspend fun execute(process: TestDataRetrievalProcess, outputPath: String): List<ExecutionOutput> {
            executeCalled = true
            lastProcess = process
            lastOutputPath = outputPath
            return mockOutputs
        }

        // Non-suspend wrapper for testing
        fun executeSync(process: TestDataRetrievalProcess, outputPath: String): List<ExecutionOutput> {
            executeCalled = true
            lastProcess = process
            lastOutputPath = outputPath
            return mockOutputs
        }

        // Expose protected methods for testing
        fun testCheckCache(process: TestDataRetrievalProcess, fileName: String, cacheDir: String): String? {
            return checkCache(process, fileName, cacheDir)
        }

        fun testCreateSuccessOutput(outputId: String, filePath: String, format: FileFormat, fileSize: Long? = null): ExecutionOutput {
            return createSuccessOutput(outputId, filePath, format, fileSize)
        }

        fun testCreateFailureOutput(outputId: String, errorMessage: String): ExecutionOutput {
            return createFailureOutput(outputId, errorMessage)
        }
    }

    // Test implementation of DataRetrievalProcess
    private class TestDataRetrievalProcess(
        override val name: String = "TestProcess",
        override val description: String = "Test process",
        override val retrievalConfig: RetrievalConfig = RetrievalConfig(),
        override val supportsCaching: Boolean = true,
        override val requiresAuthentication: Boolean = false
    ) : DataRetrievalProcess

    @Test
    fun `execute calls abstract method with correct parameters`() {
        val executor = TestDataRetrievalExecutor()
        val process = TestDataRetrievalProcess(name = "test-process")
        val outputPath = "/tmp/output"

        executor.mockOutputs = listOf(
            ExecutionOutput(
                outputId = "test-output",
                actualLocation = FileSystemSource("/tmp/file.txt", FileFormat.CSV),
                statistics = DataStatistics(),
                timestamp = Clock.System.now(),
                success = true,
                errorMessage = null
            )
        )

        val result = executor.executeSync(process, outputPath)

        assertTrue(executor.executeCalled)
        assertEquals(process, executor.lastProcess)
        assertEquals(outputPath, executor.lastOutputPath)
        assertEquals(1, result.size)
        assertEquals("test-output", result.first().outputId)
    }

    @Test
    fun `checkCache returns null when cache disabled`() {
        val executor = TestDataRetrievalExecutor()
        val process = TestDataRetrievalProcess(
            retrievalConfig = RetrievalConfig(useCache = false)
        )

        val result = executor.testCheckCache(process, "file.txt", "/cache")

        assertNull(result)
    }

    @Test
    fun `checkCache returns null when cache enabled but not implemented`() {
        val executor = TestDataRetrievalExecutor()
        val process = TestDataRetrievalProcess(
            retrievalConfig = RetrievalConfig(useCache = true)
        )

        val result = executor.testCheckCache(process, "file.txt", "/cache")

        // Current implementation always returns null (TODO comment in code)
        assertNull(result)
    }

    @Test
    fun `createSuccessOutput creates valid success output`() {
        val executor = TestDataRetrievalExecutor()
        val outputId = "output-123"
        val filePath = "/tmp/data.csv"
        val format = FileFormat.CSV
        val fileSize = 1024L

        val output = executor.testCreateSuccessOutput(outputId, filePath, format, fileSize)

        assertEquals(outputId, output.outputId)
        assertTrue(output.success)
        assertNull(output.errorMessage)
        assertNotNull(output.actualLocation)
        assertEquals(filePath, (output.actualLocation as FileSystemSource).path)
        assertEquals(format, (output.actualLocation as FileSystemSource).format)
        assertEquals(fileSize, output.statistics.byteSize)
        assertNotNull(output.timestamp)
    }

    @Test
    fun `createSuccessOutput creates valid success output without fileSize`() {
        val executor = TestDataRetrievalExecutor()
        val outputId = "output-456"
        val filePath = "/tmp/data.json"
        val format = FileFormat.JSON

        val output = executor.testCreateSuccessOutput(outputId, filePath, format)

        assertEquals(outputId, output.outputId)
        assertTrue(output.success)
        assertNull(output.errorMessage)
        assertEquals(filePath, (output.actualLocation as FileSystemSource).path)
        assertEquals(format, (output.actualLocation as FileSystemSource).format)
        assertNull(output.statistics.byteSize)
    }

    @Test
    fun `createFailureOutput creates valid failure output`() {
        val executor = TestDataRetrievalExecutor()
        val outputId = "failed-output"
        val errorMessage = "Network timeout occurred"

        val output = executor.testCreateFailureOutput(outputId, errorMessage)

        assertEquals(outputId, output.outputId)
        assertFalse(output.success)
        assertEquals(errorMessage, output.errorMessage)
        assertNotNull(output.actualLocation)
        assertEquals("", (output.actualLocation as FileSystemSource).path)
        assertEquals(FileFormat.BINARY, (output.actualLocation as FileSystemSource).format)
        assertNotNull(output.statistics)
        assertNotNull(output.timestamp)
    }

    @Test
    fun `createSuccessOutput with different file formats`() {
        val executor = TestDataRetrievalExecutor()

        val csvOutput = executor.testCreateSuccessOutput("csv", "/tmp/data.csv", FileFormat.CSV)
        val jsonOutput = executor.testCreateSuccessOutput("json", "/tmp/data.json", FileFormat.JSON)
        val binaryOutput = executor.testCreateSuccessOutput("binary", "/tmp/data.bin", FileFormat.BINARY)

        assertEquals(FileFormat.CSV, (csvOutput.actualLocation as FileSystemSource).format)
        assertEquals(FileFormat.JSON, (jsonOutput.actualLocation as FileSystemSource).format)
        assertEquals(FileFormat.BINARY, (binaryOutput.actualLocation as FileSystemSource).format)
    }

    @Test
    fun `multiple execute calls work correctly`() {
        val executor = TestDataRetrievalExecutor()
        val process1 = TestDataRetrievalProcess(name = "process1")
        val process2 = TestDataRetrievalProcess(name = "process2")

        executor.mockOutputs = listOf(executor.testCreateSuccessOutput("out1", "/file1", FileFormat.CSV))
        executor.executeSync(process1, "/path1")

        executor.mockOutputs = listOf(executor.testCreateFailureOutput("out2", "error"))
        executor.executeSync(process2, "/path2")

        assertEquals(process2, executor.lastProcess)
        assertEquals("/path2", executor.lastOutputPath)
    }

    @Test
    fun `process with custom retrieval config`() {
        val customConfig = RetrievalConfig(
            maxRetries = 5,
            timeoutMs = 60000,
            useCache = false,
            cacheDir = "/custom/cache",
            customParams = mapOf("param1" to "value1")
        )
        val process = TestDataRetrievalProcess(
            name = "custom-process",
            retrievalConfig = customConfig,
            supportsCaching = false,
            requiresAuthentication = true
        )

        assertEquals("custom-process", process.name)
        assertEquals(customConfig, process.retrievalConfig)
        assertFalse(process.supportsCaching)
        assertTrue(process.requiresAuthentication)
    }
}
