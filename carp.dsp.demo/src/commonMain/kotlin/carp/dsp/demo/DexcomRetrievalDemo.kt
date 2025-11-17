package carp.dsp.demo

import carp.dsp.core.domain.execution.ExecutionFactory
import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.application.data.DataRegistry
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.WriteMode
import dk.cachet.carp.analytics.domain.execution.ExecutionStrategy
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID

/**
 * Demo script that retrieves DEXCOM.csv files from PhysioNet's Big IDEAs dataset.
 *
 * Dataset: Big IDEAs Glycemic Control and Wearables
 * URL: https://physionet.org/content/big-ideas-glycemic-wearable/1.1.2/
 *
 * This dataset contains continuous glucose monitoring data and wearable sensor data
 * from the Big IDEAs study.
 */
object DexcomRetrievalDemo {

    /**
     * Creates a workflow that retrieves all DEXCOM.csv files from the Big IDEAs dataset.
     *
     * The dataset contains DEXCOM.csv files for multiple subjects in subdirectories.
     */
    fun createDexcomRetrievalWorkflow(): Workflow {

        // Use user's home directory for downloads on Windows
        val downloadBaseDir = System.getProperty("user.home") + "/physionet-downloads/big-ideas"

        // List of subject IDs in the dataset
        // Based on the actual PhysioNet dataset structure: 001, 002, 005, etc.
        val subjectIds = listOf(
            "001", "002", "005", "006", "007", "008", "009", "010",
            "011", "012", "013", "014", "015", "016"
        )

        // Create output specs for each Dexcom file
        val outputs = subjectIds.map { subjectId ->
            OutputDataSpec(
                identifier = "${subjectId}_dexcom",
                name = "Subject $subjectId Dexcom Data",
                description = "Continuous glucose monitoring data for subject $subjectId",
                destination = FileDestination(
                    path = "$downloadBaseDir/$subjectId/Dexcom_$subjectId.csv",
                    format = FileFormat.CSV,
                    writeMode = WriteMode.OVERWRITE
                )
            )
        }

        // Create files list with actual paths: 005/Dexcom_005.csv
        val files = subjectIds.map { subjectId ->
            "$subjectId/Dexcom_$subjectId.csv"
        }

        // Create retrieval step
        val retrievalStep = Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "Retrieve DEXCOM Files",
                description = "Downloads all DEXCOM.csv files from Big IDEAs dataset"
            ),
            inputs = emptyList(),
            outputs = outputs,
            process = PhysioNetRetrievalProcess(
                datasetId = "big-ideas-glycemic-wearable",
                version = "1.1.2",
                files = files,
                expectedFormat = FileFormat.CSV,
                retrievalConfig = RetrievalConfig(
                    maxRetries = 3,
                    timeoutMs = 120_000, // 2 minutes per file
                    useCache = true,
                    cacheDir = "/tmp/physionet-cache"
                )
            )
        )

        // Create workflow
        val workflow = Workflow(
            metadata = WorkflowMetadata(
                name = "Big IDEAs DEXCOM Data Retrieval",
                description = "Retrieves all DEXCOM.csv files from PhysioNet Big IDEAs dataset",
                id = UUID.randomUUID()
            )
        )
        workflow.addComponent(retrievalStep)

        return workflow
    }

    /**
     * Executes the DEXCOM retrieval workflow.
     * Platform-specific implementation required.
     */
    fun execute(strategy: ExecutionStrategy) {
        println("=".repeat(80))
        println("Big IDEAs DEXCOM Data Retrieval Demo")
        println("=".repeat(80))
        println()
        println("Dataset: Big IDEAs Glycemic Control and Wearables")
        println("Version: 1.1.2")
        println("URL: https://physionet.org/content/big-ideas-glycemic-wearable/1.1.2/")
        println()

        val downloadDir = System.getProperty("user.home") + "/physionet-downloads/big-ideas"
        println("This demo will retrieve Dexcom CSV files from the dataset.")
        println("Files will be saved to: $downloadDir/NNN/Dexcom_NNN.csv")
        println()

        // Create workflow
        val workflow = createDexcomRetrievalWorkflow()

        // Set up execution
        val dataRegistry = DataRegistry()
        val executionFactory = ExecutionFactory()

        // Execute workflow
        println("Starting workflow execution...")
        println("-".repeat(80))

        try {
            strategy.execute(workflow, executionFactory)

            println("-".repeat(80))
            println("✅ Workflow execution completed successfully!")
            println()

            // Display summary
            val outputs = dataRegistry.toExecutionOutputs()
            if (outputs.isNotEmpty()) {
                println("Retrieved ${outputs.size} DEXCOM files:")
                outputs.forEach { output ->
                    val location = output.actualLocation
                    if (location is dk.cachet.carp.analytics.domain.data.FileSystemSource) {
                        val status = if (output.success) "✅" else "❌"
                        println("  $status ${output.outputId}: ${location.path}")
                        if (!output.success) {
                            println("     Error: ${output.errorMessage}")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            println("-".repeat(80))
            println("❌ Workflow execution failed!")
            println("Error: ${e.message}")
            e.printStackTrace()
        }

        println()
        println("=".repeat(80))
    }
}


