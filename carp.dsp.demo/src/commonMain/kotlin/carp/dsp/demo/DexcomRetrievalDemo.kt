package carp.dsp.demo

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.domain.execution.ExecutionFactory
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

private const val LINE_WIDTH = 80

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
        printHeader()

        val workflow = createDexcomRetrievalWorkflow()
        val dataRegistry = DataRegistry()
        val executionFactory = ExecutionFactory()

        println("Starting workflow execution...")
        println("-".repeat(LINE_WIDTH))

        try {
            strategy.execute(workflow, executionFactory)
            printSuccessResults(dataRegistry)
        } catch (e: IllegalStateException) {
            printError(e)
        }

        println()
        println("=".repeat(LINE_WIDTH))
    }

    /**
     * Prints the demo header information.
     */
    private fun printHeader() {
        println("=".repeat(LINE_WIDTH))
        println("Big IDEAs DEXCOM Data Retrieval Demo")
        println("=".repeat(LINE_WIDTH))
        println()
        println("Dataset: Big IDEAs Glycemic Control and Wearables")
        println("Version: 1.1.2")
        println("URL: https://physionet.org/content/big-ideas-glycemic-wearable/1.1.2/")
        println()

        val downloadDir = System.getProperty("user.home") + "/physionet-downloads/big-ideas"
        println("This demo will retrieve Dexcom CSV files from the dataset.")
        println("Files will be saved to: $downloadDir/NNN/Dexcom_NNN.csv")
        println()
    }

    /**
     * Prints successful execution results.
     */
    private fun printSuccessResults(dataRegistry: DataRegistry) {
        println("-".repeat(LINE_WIDTH))
        println("✅ Workflow execution completed successfully!")
        println()

        val outputs = dataRegistry.toExecutionOutputs()
        if (outputs.isEmpty()) return

        println("Retrieved ${outputs.size} DEXCOM files:")
        outputs.forEach { output ->
            printOutputResult(output)
        }
    }

    /**
     * Prints a single output result.
     */
    private fun printOutputResult(output: dk.cachet.carp.analytics.domain.data.ExecutionOutput) {
        val location = output.actualLocation
        if (location !is dk.cachet.carp.analytics.domain.data.FileSystemSource) return

        val status = if (output.success) "✅" else "❌"
        println("  $status ${output.outputId}: ${location.path}")
        if (!output.success) {
            println("     Error: ${output.errorMessage}")
        }
    }

    /**
     * Prints error information.
     */
    private fun printError(e: IllegalStateException) {
        println("-".repeat(LINE_WIDTH))
        println("❌ Workflow execution failed!")
        println("Error: ${e.message}")
        println("Error details: ${e.javaClass.simpleName}")
    }
}
