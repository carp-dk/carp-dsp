package carp.dsp.demo

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.Authentication
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.WriteMode
import dk.cachet.carp.analytics.domain.process.WorkflowProcess
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID

/**
 * Example: Creating a workflow that retrieves data from PhysioNet.
 *
 * This example shows how to:
 * 1. Define a PhysioNet retrieval process
 * 2. Configure outputs to go to specific locations
 * 3. Create a workflow step
 * 4. Chain with analysis steps
 */
object PhysioNetRetrievalExample {

    /**
     * Example 1: Simple retrieval of MIMIC-III demo dataset.
     */
    fun createMimicRetrievalWorkflow(): Workflow {

        // Step 1: Retrieve data from PhysioNet
        val retrievalStep = Step(
            metadata = StepMetadata(
                name = "Retrieve MIMIC-III Demo Dataset",
                id = UUID.randomUUID(),
                description = "Downloads MIMIC-III demo dataset from PhysioNet"
            ),
            inputs = emptyList(), // No inputs - retrieval is the starting point
            outputs = listOf(
                OutputDataSpec(
                    identifier = "admissions_data",
                    name = "Hospital Admissions Data",
                    description = "MIMIC-III demo admissions table",
                    destination = FileDestination(
                        path = "/data/mimic-demo/ADMISSIONS.csv",
                        format = FileFormat.CSV,
                        writeMode = WriteMode.OVERWRITE
                    )
                ),
                OutputDataSpec(
                    identifier = "patients_data",
                    name = "Patient Demographics",
                    description = "MIMIC-III demo patients table",
                    destination = FileDestination(
                        path = "/data/mimic-demo/PATIENTS.csv",
                        format = FileFormat.CSV,
                        writeMode = WriteMode.OVERWRITE
                    )
                )
            ),
            process = PhysioNetRetrievalProcess(
                datasetId = "mimic-iii-demo",
                version = "1.4",
                files = listOf("ADMISSIONS.csv", "PATIENTS.csv"),
                expectedFormat = FileFormat.CSV,
                retrievalConfig = RetrievalConfig(
                    maxRetries = 3,
                    timeoutMs = 60_000, // 60 seconds
                    useCache = true
                )
            )
        )

        val workflow = Workflow(
            metadata = WorkflowMetadata(
                name = "MIMIC-III Data Retrieval",
                description = "Retrieves MIMIC-III demo dataset from PhysioNet",
                id = UUID.randomUUID()
            )
        )
        workflow.addComponent(retrievalStep)

        return workflow
    }

    /**
     * Example 2: Retrieval with authentication for restricted dataset.
     */
    fun createRestrictedDatasetWorkflow(username: String, password: String): Workflow {

        val retrievalStep = Step(
            metadata = StepMetadata(
                name = "Retrieve PTB-XL ECG Dataset",
                id = UUID.randomUUID(),
                description = "Downloads PTB-XL ECG dataset with authentication"
            ),
            inputs = emptyList(),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "ecg_data",
                    name = "ECG Recordings",
                    destination = FileDestination(
                        path = "/data/ptb-xl/records.csv",
                        format = FileFormat.CSV
                    )
                ),
                OutputDataSpec(
                    identifier = "ecg_metadata",
                    name = "ECG Metadata",
                    destination = FileDestination(
                        path = "/data/ptb-xl/metadata.csv",
                        format = FileFormat.CSV
                    )
                )
            ),
            process = PhysioNetRetrievalProcess(
                datasetId = "ptb-xl",
                version = "1.0.3",
                files = listOf("ptbxl_database.csv", "scp_statements.csv"),
                authentication = Authentication.Basic(username, password),
                retrievalConfig = RetrievalConfig(
                    maxRetries = 5,
                    timeoutMs = 120_000, // 2 minutes for larger files
                    useCache = true,
                    cacheDir = "/tmp/physionet-cache"
                )
            )
        )

        val workflow = Workflow(
            metadata = WorkflowMetadata(
                name = "PTB-XL ECG Data Retrieval",
                description = "Retrieves PTB-XL ECG dataset with authentication",
                id = UUID.randomUUID()
            )
        )
        workflow.addComponent(retrievalStep)

        return workflow
    }

    /**
     * Example 3: Multi-step workflow - Retrieve then Analyze.
     *
     * This shows how retrieval integrates with analysis steps.
     */
    fun createRetrieveAndAnalyzeWorkflow(): Workflow {

        // Step 1: Retrieve data
        val retrievalStep = Step(
            metadata = StepMetadata(
                name = "Retrieve Dataset",
                id = UUID.randomUUID(),
                description = "Downloads MIMIC-III demo dataset"
            ),
            inputs = emptyList(),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "raw_data",
                    name = "Raw Data",
                    destination = RegistryDestination(
                        key = "physionet_raw_data",
                        overwrite = true
                    )
                )
            ),
            process = PhysioNetRetrievalProcess(
                datasetId = "mimic-iii-demo",
                version = "1.4",
                files = listOf("ADMISSIONS.csv")
            )
        )

        // Step 2: Clean/process the data
        // (This would use an AnalysisProcess - placeholder for now)
        val cleaningStep = Step(
            metadata = StepMetadata(
                name = "Clean Retrieved Data",
                id = UUID.randomUUID(),
                description = "Clean and validate the downloaded data"
            ),
            inputs = listOf(
                InputDataSpec(
                    identifier = "raw_data",
                    name = "Raw Data Input",
                    source = InMemorySource(registryKey = "physionet_raw_data"),
                    required = true
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    identifier = "cleaned_data",
                    name = "Cleaned Data",
                    destination = FileDestination(
                        path = "/data/processed/admissions_clean.csv",
                        format = FileFormat.CSV
                    )
                )
            ),
            process = object : WorkflowProcess {
                override val name = "Data Cleaning"
                override val description = "Clean and validate retrieved data"
            }
        )

        val workflow = Workflow(
            metadata = WorkflowMetadata(
                name = "Retrieve and Analyze MIMIC-III",
                description = "Complete workflow: retrieve, clean, analyze",
                id = UUID.randomUUID()
            )
        )
        workflow.addComponent(retrievalStep)
        workflow.addComponent(cleaningStep)

        return workflow
    }
}

