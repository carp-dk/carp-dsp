package carp.dsp.core.application.process

import carp.dsp.core.domain.process.DataRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.FileFormat

/**
 * Process for retrieving data from PhysioNet repository.
 *
 * PhysioNet (https://physionet.org/) is a repository of freely-available medical research data,
 * managed by the MIT Laboratory for Computational Physiology.
 *
 * This process handles:
 * - Dataset discovery and metadata retrieval
 * - Authentication (for restricted datasets)
 * - File download with progress tracking
 * - Format validation
 * - Local caching
 *
 * Example usage:
 * ```
 * val process = PhysioNetRetrievalProcess(
 *     datasetId = "mimic-iii-demo",
 *     version = "1.4",
 *     files = listOf("ADMISSIONS.csv", "PATIENTS.csv"),
 *     authentication = Authentication.Basic("username", "password")
 * )
 * ```
 */
data class PhysioNetRetrievalProcess(
    /**
     * PhysioNet dataset identifier (e.g., "mimic-iii-demo", "ptb-xl").
     */
    val datasetId: String,

    /**
     * Dataset version (e.g., "1.4", "1.0.0").
     */
    val version: String,

    /**
     * Specific files to download from the dataset.
     * If empty, downloads all files (use with caution for large datasets).
     */
    val files: List<String> = emptyList(),

    /**
     * Expected format of the files being retrieved.
     * Used for validation after download.
     */
    val expectedFormat: FileFormat = FileFormat.CSV,

    /**
     * Authentication credentials for restricted datasets.
     * PhysioNet requires credentialed access for some datasets.
     */
    val authentication: Authentication? = null,

    /**
     * Base URL for PhysioNet repository.
     * Can be overridden for testing or mirror sites.
     */
    val baseUrl: String = "https://physionet.org/files",

    /**
     * Configuration for the retrieval operation.
     */
    override val retrievalConfig: RetrievalConfig = RetrievalConfig(),

    /**
     * Human-readable name for this process.
     */
    override val name: String = "PhysioNet Data Retrieval",

    /**
     * Description of what this process does.
     */
    override val description: String? = "Retrieves dataset '$datasetId' version $version from PhysioNet"

) : DataRetrievalProcess {

    companion object {
        private const val BYTES_PER_KB = 1024L
        private const val BYTES_PER_MB = BYTES_PER_KB * 1024L
        private const val MIMIC_III_DEMO_SIZE_MB = 26L
        private const val PTB_XL_SIZE_MB = 850L
    }

    override val requiresAuthentication: Boolean
        get() = authentication != null

    /**
     * Constructs the download URL for a specific file.
     */
    fun getFileUrl(fileName: String): String {
        return "$baseUrl/$datasetId/$version/$fileName"
    }

    /**
     * Constructs the download URL for dataset metadata.
     */
    fun getMetadataUrl(): String {
        return "$baseUrl/$datasetId/$version/SHA256SUMS.txt"
    }

    /**
     * Validates the dataset ID format.
     */
    fun validateDatasetId(): Boolean {
        // PhysioNet dataset IDs typically use lowercase with hyphens
        return datasetId.matches(Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$"))
    }

    /**
     * Estimates total download size based on known dataset sizes.
     * Returns null if size cannot be estimated.
     */
    fun estimateDownloadSize(): Long? {
        // This could be enhanced with actual metadata lookup
        return when (datasetId) {
            "mimic-iii-demo" -> MIMIC_III_DEMO_SIZE_MB * BYTES_PER_MB // ~26 MB
            "ptb-xl" -> PTB_XL_SIZE_MB * BYTES_PER_MB // ~850 MB
            else -> null
        }
    }
}
