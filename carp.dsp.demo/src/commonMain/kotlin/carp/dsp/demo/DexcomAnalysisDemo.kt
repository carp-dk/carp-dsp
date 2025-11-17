package carp.dsp.demo

import carp.dsp.core.application.process.PhysioNetRetrievalProcess
import carp.dsp.core.application.process.PythonProcess
import carp.dsp.core.application.environment.CondaEnvironment
import carp.dsp.core.domain.process.RetrievalConfig
import dk.cachet.carp.analytics.domain.data.*
import dk.cachet.carp.analytics.domain.environment.Environment
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import java.io.File
import kotlin.String


/**
 * Extended DEXCOM demo that downloads data and runs cgmquantify analysis.
 *
 * This workflow demonstrates:
 * 1. Downloading DEXCOM data from PhysioNet
 * 2. Running Python-based CGM analysis using cgmquantify
 * 3. Chaining multiple steps together
 *
 * Requirements:
 * - Conda environment with cgmquantify installed:
 *   conda create -n cgm-analysis python=3.11 pandas cgmquantify -c conda-forge
 */
object DexcomAnalysisDemo {

    /**
     * Creates a complete workflow that downloads and analyzes DEXCOM data.
     *
     * The workflow has two steps:
     * 1. Download DEXCOM files from PhysioNet
     * 2. Analyze each file using cgmquantify
     */
    fun createDexcomAnalysisWorkflow(
        subjectIds: List<String> = listOf("001", "002", "005"),
        condaEnv: String = "cgm-analysis"
    ): Workflow {

        val downloadBaseDir = System.getProperty("user.home") + "/physionet-downloads/big-ideas"
        val resultsBaseDir = System.getProperty("user.home") + "/cgm-analysis-results"
        val scriptPath = getScriptPath()

        // Step 1: Download DEXCOM files
        val downloadStep = createDownloadStep(subjectIds, downloadBaseDir)

        // Step 2+: Analyze downloaded files (one step per subject)
        val analysisSteps = createAnalysisSteps(subjectIds, downloadBaseDir, resultsBaseDir, scriptPath, condaEnv)

        // Create workflow
        val workflow = Workflow(
            metadata = WorkflowMetadata(
                name = "DEXCOM Download and Analysis Workflow",
                description = "Downloads DEXCOM data from PhysioNet and analyzes it using cgmquantify",
                id = UUID.randomUUID()
            )
        )

        workflow.addComponent(downloadStep)
        analysisSteps.forEach { workflow.addComponent(it) }

        return workflow
    }

    /**
     * Creates the download step.
     */
    private fun createDownloadStep(subjectIds: List<String>, downloadBaseDir: String): Step {
        val outputs = subjectIds.map { subjectId ->
            OutputDataSpec(
                identifier = "${subjectId}_dexcom",
                name = "Subject $subjectId Dexcom Data",
                description = "CGM data for subject $subjectId",
                destination = FileDestination(
                    path = "$downloadBaseDir/$subjectId/Dexcom_$subjectId.csv",
                    format = FileFormat.CSV,
                    writeMode = WriteMode.OVERWRITE
                )
            )
        }

        val files = subjectIds.map { subjectId ->
            "$subjectId/Dexcom_$subjectId.csv"
        }

        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "Download DEXCOM Files",
                description = "Downloads DEXCOM CSV files from PhysioNet Big IDEAs dataset"
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
                    timeoutMs = 120_000,
                    useCache = true,
                    cacheDir = System.getProperty("java.io.tmpdir") + "/physionet-cache"
                )
            )
        )
    }

    /**
     * Creates the analysis step using Python and cgmquantify.
     * Creates one step per subject for individual processing.
     */
    private fun createAnalysisSteps(
        subjectIds: List<String>,
        downloadBaseDir: String,
        resultsBaseDir: String,
        scriptPath: String,
        condaEnv: String
    ): List<Step> {
        return subjectIds.map { subjectId ->
            // TODO: use output of download step as input
            // TODO: User this as a use case for the workflow validator
            // TODO: Use this example as a parellel processing demo
            val input = InputDataSpec(
                identifier = "${subjectId}_input",
                name = "Subject $subjectId Input Data",
                description = "Downloaded DEXCOM data for subject $subjectId",
                source = FileSystemSource(
                    path = "$downloadBaseDir/$subjectId/Dexcom_$subjectId.csv",
                    format = FileFormat.CSV
                )
            )

            val output = OutputDataSpec(
                identifier = "${subjectId}_analysis",
                name = "Subject $subjectId Analysis Results",
                description = "CGM metrics for subject $subjectId",
                destination = FileDestination(
                    path = "$resultsBaseDir/$subjectId/analysis.json",
                    format = FileFormat.JSON,
                    writeMode = WriteMode.OVERWRITE
                )
            )

            // Create Python process for this subject
            val process = PythonProcess(
                name = "CGM Analysis - Subject $subjectId",
                description = "Analyzes CGM data using cgmquantify package",
                executionContext = ExecutionContext(
                    environment = CondaEnvironment(name = condaEnv, dependencies = listOf(
                        "pandas",
                        "pip:git+https://github.com/brinnaebent/cgmquantify.git"
                    )),
                    envVariables = emptyMap()
                ),
                scriptPath = scriptPath,
                arguments = listOf("--verbose"),
                useCondaRun = true
            )

            Step(
                metadata = StepMetadata(
                    id = UUID.randomUUID(),
                    name = "Analyze CGM Data - Subject $subjectId",
                    description = "Runs cgmquantify analysis for subject $subjectId"
                ),
                inputs = listOf(input),
                outputs = listOf(output),
                process = process
            )
        }
    }

    /**
     * Gets the path to the analysis script.
     * Tries to find it in the demo/scripts directory.
     */
    private fun getScriptPath(): String {
        // Try to find script relative to current working directory
        val possiblePaths = listOf(
            "carp.dsp.demo/scripts/analyze_cgm.py",
            "scripts/analyze_cgm.py",
            "../scripts/analyze_cgm.py",
            System.getProperty("user.dir") + "/carp.dsp.demo/scripts/analyze_cgm.py"
        )

        for (path in possiblePaths) {
            if (File(path).exists()) {
                return File(path).absolutePath
            }
        }

        // Default to relative path and hope it works
        println("Warning: Could not find analyze_cgm.py script in expected locations")
        println("Searched: ${possiblePaths.joinToString(", ")}")
        println("Using default: scripts/analyze_cgm.py")

        return "scripts/analyze_cgm.py"
    }

    /**
     * Creates a simplified workflow for a single subject (useful for testing).
     */
    fun createSingleSubjectWorkflow(subjectId: String = "001", condaEnv: String = "cgm-analysis"): Workflow {
        return createDexcomAnalysisWorkflow(listOf(subjectId), condaEnv)
    }
}

