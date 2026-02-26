package carp.dsp.core.infrastructure.process

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.process.DataRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataRetrievalExecutorFactoryTest {

    // Test implementation of an unknown DataRetrievalProcess
    private class UnknownRetrievalProcess(
        override val name: String = "UnknownProcess",
        override val description: String = "Unknown test process",
        override val retrievalConfig: RetrievalConfig = RetrievalConfig()
    ) : DataRetrievalProcess

    @Test
    fun `getExecutor returns PhysioNetRetrievalExecutor for PhysioNetRetrievalProcess`() {
        val factory = DataRetrievalExecutorFactory()
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = listOf("PATIENTS.csv")
        )

        val executor = factory.getExecutor(process)

        assertNotNull(executor)
        assertTrue(executor is PhysioNetRetrievalExecutor)

        // Clean up
        factory.close()
    }

    @Test
    fun `getExecutor throws IllegalArgumentException for unknown process type`() {
        val factory = DataRetrievalExecutorFactory()
        val unknownProcess = UnknownRetrievalProcess()

        val exception = assertFailsWith<IllegalArgumentException> {
            factory.getExecutor(unknownProcess)
        }

        assertTrue(exception.message!!.contains("No executor available for"))
        assertTrue(exception.message!!.contains("UnknownRetrievalProcess"))

        // Clean up
        factory.close()
    }

    @Test
    fun `getExecutor works with different PhysioNet configurations`() {
        val factory = DataRetrievalExecutorFactory()

        // Test with minimal configuration
        val minimalProcess = PhysioNetRetrievalProcess(
            datasetId = "test-dataset",
            version = "1.0"
        )
        val minimalExecutor = factory.getExecutor(minimalProcess)
        assertNotNull(minimalExecutor)
        assertTrue(minimalExecutor is PhysioNetRetrievalExecutor)

        // Test with full configuration
        val fullProcess = PhysioNetRetrievalProcess(
            datasetId = "ptb-xl",
            version = "1.0.1",
            files = listOf("records100.csv", "records500.csv"),
            baseUrl = "https://custom.physionet.org/files",
            retrievalConfig = RetrievalConfig(
                maxRetries = 5,
                timeoutMs = 60000,
                useCache = true,
                cacheDir = "/tmp/cache"
            )
        )
        val fullExecutor = factory.getExecutor(fullProcess)
        assertNotNull(fullExecutor)
        assertTrue(fullExecutor is PhysioNetRetrievalExecutor)

        // Clean up
        factory.close()
    }

    @Test
    fun `factory creates multiple executors independently`() {
        val factory = DataRetrievalExecutorFactory()

        val process1 = PhysioNetRetrievalProcess(
            datasetId = "dataset1",
            version = "1.0"
        )
        val process2 = PhysioNetRetrievalProcess(
            datasetId = "dataset2",
            version = "2.0"
        )

        val executor1 = factory.getExecutor(process1)
        val executor2 = factory.getExecutor(process2)

        assertNotNull(executor1)
        assertNotNull(executor2)
        assertTrue(executor1 is PhysioNetRetrievalExecutor)
        assertTrue(executor2 is PhysioNetRetrievalExecutor)

        // They should be different instances but same type
        assertTrue(executor1::class == executor2::class)

        // Clean up
        factory.close()
    }

    @Test
    fun `close method completes without exceptions`() {
        val factory = DataRetrievalExecutorFactory()

        // Use the factory first
        val process = PhysioNetRetrievalProcess("test", "1.0")
        val executor = factory.getExecutor(process)
        assertNotNull(executor)

        // Close should not throw
        factory.close()

        // Can still get executors after close (lazy HTTP client)
        val newExecutor = factory.getExecutor(process)
        assertNotNull(newExecutor)

        // Close again should not throw
        factory.close()
    }

    @Test
    fun `factory handles generic type parameters correctly`() {
        val factory = DataRetrievalExecutorFactory()

        // Test with explicit generic type
        val process: DataRetrievalProcess = PhysioNetRetrievalProcess("test", "1.0")
        val executor: DataRetrievalExecutor<DataRetrievalProcess> = factory.getExecutor(process)

        assertNotNull(executor)
        assertTrue(executor is PhysioNetRetrievalExecutor)

        // Clean up
        factory.close()
    }

    @Test
    fun `getExecutor handles PhysioNet process with authentication`() {
        val factory = DataRetrievalExecutorFactory()
        val processWithAuth = PhysioNetRetrievalProcess(
            datasetId = "restricted-dataset",
            version = "1.0",
            files = listOf("sensitive_data.csv")
        )

        val executor = factory.getExecutor(processWithAuth)

        assertNotNull(executor)
        assertTrue(executor is PhysioNetRetrievalExecutor)

        // Clean up
        factory.close()
    }

    @Test
    fun `getExecutor preserves process type information`() {
        val factory = DataRetrievalExecutorFactory()
        val physioNetProcess = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii",
            version = "1.4",
            files = listOf("ADMISSIONS.csv", "PATIENTS.csv")
        )

        // The generic type should be preserved
        val executor: DataRetrievalExecutor<PhysioNetRetrievalProcess> = factory.getExecutor(physioNetProcess)

        assertNotNull(executor)
        assertTrue(executor is PhysioNetRetrievalExecutor)

        // Clean up
        factory.close()
    }

    @Test
    fun `multiple factory instances are independent`() {
        val factory1 = DataRetrievalExecutorFactory()
        val factory2 = DataRetrievalExecutorFactory()

        val process = PhysioNetRetrievalProcess("test", "1.0")

        val executor1 = factory1.getExecutor(process)
        val executor2 = factory2.getExecutor(process)

        assertNotNull(executor1)
        assertNotNull(executor2)
        assertTrue(executor1 is PhysioNetRetrievalExecutor)
        assertTrue(executor2 is PhysioNetRetrievalExecutor)

        // Clean up both factories
        factory1.close()
        factory2.close()
    }
}
