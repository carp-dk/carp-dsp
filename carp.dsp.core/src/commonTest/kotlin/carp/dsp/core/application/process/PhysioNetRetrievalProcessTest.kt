package carp.dsp.core.application.process

import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.FileFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysioNetRetrievalProcessTest {

    @Test
    fun testDefaultConstruction() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        assertEquals("mimic-iii-demo", process.datasetId)
        assertEquals("1.4", process.version)
        assertTrue(process.files.isEmpty())
        assertEquals(FileFormat.CSV, process.expectedFormat)
        assertNull(process.authentication)
        assertEquals("https://physionet.org/files", process.baseUrl)
        assertEquals("PhysioNet Data Retrieval", process.name)
        assertFalse(process.requiresAuthentication)
    }

    @Test
    fun testWithAuthentication() {
        val auth = Authentication.Basic("testuser", "testpass")
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii",
            version = "1.4",
            authentication = auth
        )

        assertEquals(auth, process.authentication)
        assertTrue(process.requiresAuthentication)
    }

    @Test
    fun testWithSpecificFiles() {
        val files = listOf("ADMISSIONS.csv", "PATIENTS.csv", "DIAGNOSES.csv")
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = files
        )

        assertEquals(3, process.files.size)
        assertEquals(files, process.files)
    }

    @Test
    fun testWithCustomFileFormat() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "ptb-xl",
            version = "1.0.1",
            expectedFormat = FileFormat.PARQUET
        )

        assertEquals(FileFormat.PARQUET, process.expectedFormat)
    }

    @Test
    fun testWithCustomBaseUrl() {
        val customUrl = "https://mirror.physionet.org/files"
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            baseUrl = customUrl
        )

        assertEquals(customUrl, process.baseUrl)
    }

    @Test
    fun testWithCustomRetrievalConfig() {
        val config = RetrievalConfig(
            maxRetries = 5,
            timeoutMs = 60_000,
            useCache = false,
            cacheDir = "/tmp/physionet-cache"
        )
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            retrievalConfig = config
        )

        assertEquals(5, process.retrievalConfig.maxRetries)
        assertEquals(60_000, process.retrievalConfig.timeoutMs)
        assertFalse(process.retrievalConfig.useCache)
        assertEquals("/tmp/physionet-cache", process.retrievalConfig.cacheDir)
    }

    @Test
    fun testGetFileUrl() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val fileUrl = process.getFileUrl("ADMISSIONS.csv")
        assertEquals("https://physionet.org/files/mimic-iii-demo/1.4/ADMISSIONS.csv", fileUrl)
    }

    @Test
    fun testGetFileUrlWithCustomBaseUrl() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "ptb-xl",
            version = "1.0.1",
            baseUrl = "https://mirror.physionet.org/files"
        )

        val fileUrl = process.getFileUrl("ptbxl_database.csv")
        assertEquals("https://mirror.physionet.org/files/ptb-xl/1.0.1/ptbxl_database.csv", fileUrl)
    }

    @Test
    fun testGetMetadataUrl() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val metadataUrl = process.getMetadataUrl()
        assertEquals("https://physionet.org/files/mimic-iii-demo/1.4/SHA256SUMS.txt", metadataUrl)
    }

    @Test
    fun testValidateDatasetId_Valid() {
        val validIds = listOf(
            "mimic-iii-demo",
            "ptb-xl",
            "eicu-crd",
            "mimiciv",
            "ecg-arrhythmia-database-1-0-0"
        )

        validIds.forEach { datasetId ->
            val process = PhysioNetRetrievalProcess(
                datasetId = datasetId,
                version = "1.0"
            )
            assertTrue(process.validateDatasetId(), "Expected '$datasetId' to be valid")
        }
    }

    @Test
    fun testValidateDatasetId_Invalid() {
        val invalidIds = listOf(
            "MIMIC-III", // uppercase
            "dataset_name", // underscore
            "-invalid", // starts with hyphen
            "invalid-", // ends with hyphen
            "data set", // space
            "data@set" // special character
        )

        invalidIds.forEach { datasetId ->
            val process = PhysioNetRetrievalProcess(
                datasetId = datasetId,
                version = "1.0"
            )
            assertFalse(process.validateDatasetId(), "Expected '$datasetId' to be invalid")
        }
    }

    @Test
    fun testEstimateDownloadSize_MimicIIIDemo() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val estimatedSize = process.estimateDownloadSize()
        assertNotNull(estimatedSize)
        assertEquals(26L * 1024L * 1024L, estimatedSize) // 26 MB in bytes
    }

    @Test
    fun testEstimateDownloadSize_PtbXl() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "ptb-xl",
            version = "1.0.1"
        )

        val estimatedSize = process.estimateDownloadSize()
        assertNotNull(estimatedSize)
        assertEquals(850L * 1024L * 1024L, estimatedSize) // 850 MB in bytes
    }

    @Test
    fun testEstimateDownloadSize_UnknownDataset() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "unknown-dataset",
            version = "1.0"
        )

        val estimatedSize = process.estimateDownloadSize()
        assertNull(estimatedSize)
    }

    @Test
    fun testDescriptionIncludesDatasetInfo() {
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4"
        )

        val description = process.description
        assertNotNull(description)
        assertTrue(description.contains("mimic-iii-demo"))
        assertTrue(description.contains("1.4"))
    }

    @Test
    fun testCustomNameAndDescription() {
        val customName = "My Custom Retrieval"
        val customDescription = "Custom description for testing"
        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            name = customName,
            description = customDescription
        )

        assertEquals(customName, process.name)
        assertEquals(customDescription, process.description)
    }

    @Test
    fun testDataClassCopy() {
        val original = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = listOf("ADMISSIONS.csv")
        )

        val modified = original.copy(
            version = "1.5",
            files = listOf("ADMISSIONS.csv", "PATIENTS.csv")
        )

        assertEquals("mimic-iii-demo", modified.datasetId)
        assertEquals("1.5", modified.version)
        assertEquals(2, modified.files.size)
    }

    @Test
    fun testEquality() {
        val process1 = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = listOf("ADMISSIONS.csv")
        )

        val process2 = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = listOf("ADMISSIONS.csv")
        )

        val process3 = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.5",
            files = listOf("ADMISSIONS.csv")
        )

        assertEquals(process1, process2)
        assertTrue(process1 != process3)
    }

    @Test
    fun testAuthenticationTypes() {
        // Test with Basic authentication
        val basicAuth = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii",
            version = "1.4",
            authentication = Authentication.Basic("user", "pass")
        )
        assertTrue(basicAuth.requiresAuthentication)

        // Test with Bearer authentication
        val bearerAuth = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii",
            version = "1.4",
            authentication = Authentication.Bearer("token123")
        )
        assertTrue(bearerAuth.requiresAuthentication)

        // Test with API Key authentication
        val apiKeyAuth = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii",
            version = "1.4",
            authentication = Authentication.ApiKey("key123")
        )
        assertTrue(apiKeyAuth.requiresAuthentication)
    }

    @Test
    fun testMultipleFileDownload() {
        val files = listOf(
            "ADMISSIONS.csv",
            "PATIENTS.csv",
            "DIAGNOSES_ICD.csv",
            "PROCEDURES_ICD.csv",
            "PRESCRIPTIONS.csv"
        )

        val process = PhysioNetRetrievalProcess(
            datasetId = "mimic-iii-demo",
            version = "1.4",
            files = files
        )

        assertEquals(5, process.files.size)
        files.forEach { fileName ->
            assertTrue(process.files.contains(fileName))
            val url = process.getFileUrl(fileName)
            assertTrue(url.endsWith(fileName))
        }
    }

    @Test
    fun testVersionFormats() {
        val versions = listOf("1.4", "1.0.0", "2.1.3", "1.0", "0.1.0-beta")

        versions.forEach { version ->
            val process = PhysioNetRetrievalProcess(
                datasetId = "test-dataset",
                version = version
            )
            assertEquals(version, process.version)
            assertTrue(process.getFileUrl("test.csv").contains(version))
        }
    }
}

