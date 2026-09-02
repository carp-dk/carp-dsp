package carp.dsp.demo.demos

import carp.dsp.demo.io.DemoIo
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.WorkflowPreparation
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.io.path.*
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * DBDP COVID Heart Rate & Steps Analysis Demo
 *
 * Demonstrates a complete DSP workflow that:
 * 1. Loads heart rate and steps data from a CSV file
 * 2. Detects biomarkers (elevated HR + reduced steps)
 * 3. Produces a comprehensive health alert report
 *
 * The workflow runs end-to-end using the DSP engine with:
 * - Workflow descriptor from YAML (resources/workflows/dbdp-covid-hr-steps.yaml)
 * - Input data file (resources/data/dbdp_covid_sample.csv)
 * - Python analysis scripts
 * - Pixi Python environment with pandas/numpy
 */
class DbdpCovidDemo {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            run()
        }

        fun run() {
            executeDemo()
        }

        @OptIn(ExperimentalPathApi::class)
        private fun executeDemo() {
            // Use persistent demo_results directory instead of temp dir
            val demoResultsDir = getDemoResultsDirectory()

            // Use static run ID for consistent results
            val runId = UUID.parse("00000000-0000-0000-0000-000000000002")

            try {
                println("=" * 70)
                println("DBDP COVID Heart Rate & Steps Analysis Demo")
                println("=" * 70)
                println()

                // 0. Clean up existing results before running
                if (demoResultsDir.exists()) {
                    println("Cleaning up previous results...")
                    demoResultsDir.deleteRecursively()
                }
                demoResultsDir.createDirectories()

                // 1. Load YAML workflow from resources
                val workflowYaml = loadWorkflowYaml()
                val descriptor = WorkflowYamlCodec().decodeOrThrow(workflowYaml)
                println("Workflow loaded: ${descriptor.metadata.name}")

                // 2. Lay out the files the run needs. Task script paths are relative
                // to the execution root (a command's working directory), while a
                // declared file input is resolved relative to the workflow file, so
                // the two go in different places.
                val workflowName = "dbdp_covid_heart_rate__steps_analysis"
                val executionRoot = demoResultsDir.resolve("$workflowName/run_$runId")
                executionRoot.createDirectories()
                setupWorkspaceFiles(demoResultsDir, executionRoot)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Resolve, import and plan. The workflow references a certified
                // library step, so it is written beside the run first: resolution
                // pins each reference in a steps.lock next to the workflow file.
                val workflowFile = demoResultsDir.resolve("dbdp-covid-hr-steps.yaml").toFile()
                workflowFile.writeText(workflowYaml)
                val prepared = WorkflowPreparation.prepare(workflowFile, descriptor)
                val plan = prepared.plan
                plan.validate()
                println("Execution plan generated (${plan.steps.size} steps)")

                // 4. Set up executor infrastructure
                val artefactStore = FileSystemArtefactStore(demoResultsDir.resolve("artifacts"))
                val workspaceManager = DefaultWorkspaceManager(demoResultsDir)
                val executor = DefaultPlanExecutor(
                    workspaceManager = workspaceManager,
                    artefactStore = artefactStore
                )

                // 5. Execute workflow
                println()
                println("Executing workflow...")
                println("-" * 70)
                val report = executor.run(plan, runId, prepared.provisioning)
                println("-" * 70)

                // 6. Verify execution succeeded
                if (report.status.toString() != "SUCCEEDED") {
                    println("Workflow execution failed: ${report.status}")
                    report.issues.forEach { issue ->
                        println("   - ${issue.message}")
                    }
                    return
                }
                println("Workflow execution succeeded")
                println()

                // 7. Read and display results from biomarker.json
                // The workflow creates a directory structure: <workflowName>/run_<runId>/steps/<stepIndex>_<stepName>/outputs/
                val biomarkerFile = demoResultsDir.resolve(
                    "$workflowName/run_${runId}/steps/02_analyse_hr_and_steps_for_biomarkers/outputs/biomarker-json.json"
                )
                if (biomarkerFile.exists()) {
                    printBiomarkerResults(biomarkerFile.readText())
                } else {
                    println("Biomarker output not found at: $biomarkerFile")
                    // List what's actually in the directory for debugging
                    println("Available structure:")
                }

                println()
                println("=" * 70)
                println("Demo completed successfully!")
                println("Results saved to: $demoResultsDir")
                println("=" * 70)

            } catch (e: Exception) {
                println("Error during demo execution: ${e.message}")
                e.printStackTrace()
            }
        }

        private fun loadWorkflowYaml(): String = DemoIo.loadResource("workflows/dbdp-covid-hr-steps.yaml")

        @OptIn(ExperimentalPathApi::class)
        private fun getDemoResultsDirectory(): Path = DemoIo.demoResultsDir("dbdp_covid").toPath()

        /**
         * Places the run's files where the workflow expects them.
         *
         * @param workflowDir Directory holding the workflow file. A declared file
         *   input with a relative path is resolved against this, so the dataset
         *   goes here.
         * @param executionRoot The working directory a step's command runs in, so
         *   the scripts its task arguments name go here.
         */
        @OptIn(ExperimentalPathApi::class)
        private fun setupWorkspaceFiles(workflowDir: Path, executionRoot: Path) {
            val dataDir = workflowDir.resolve("data")
            dataDir.createDirectories()
            copyResourceFile("data/dbdp_covid_sample.csv", dataDir.resolve("dbdp_covid_sample.csv"))

            val scriptsDir = executionRoot.resolve("scripts")
            scriptsDir.createDirectories()
            copyResourceFile("scripts/load_hr_steps.py", scriptsDir.resolve("load_hr_steps.py"))
            copyResourceFile("scripts/covid_hr_steps.py", scriptsDir.resolve("covid_hr_steps.py"))
            copyResourceFile("scripts/report_biomarker.py", scriptsDir.resolve("report_biomarker.py"))
        }

        private fun copyResourceFile(resourcePath: String, targetPath: Path) =
            DemoIo.copyResource(resourcePath, targetPath)

        private fun printBiomarkerResults(biomarkerJson: String) {
            try {
                val gson = Gson()
                val json = gson.fromJson(biomarkerJson, JsonObject::class.java)

                println("BIOMARKER ANALYSIS RESULTS")
                println("-" * 70)

                val baselineMetrics = json.getAsJsonObject("baseline_metrics")
                if (baselineMetrics != null) {
                    println()
                    println("Baseline Metrics (first 7 days):")
                    println("  Baseline HR Mean:               ${baselineMetrics.get("baseline_hr_mean")} bpm")
                    println("  Baseline HR Std Dev:            ${baselineMetrics.get("baseline_hr_std")} bpm")
                    println("  Baseline Steps Mean:            ${baselineMetrics.get("baseline_steps_mean")}")
                    println("  Baseline Steps Std Dev:         ${baselineMetrics.get("baseline_steps_std")}")
                }

                val recentMetrics = json.getAsJsonObject("recent_metrics")
                if (recentMetrics != null) {
                    println()
                    println("Recent Metrics (last 3 days):")
                    println("  Recent HR Mean:                 ${recentMetrics.get("recent_hr_mean")} bpm")
                    println("  Recent Steps Mean:              ${recentMetrics.get("recent_steps_mean")}")
                }

                val deviations = json.getAsJsonObject("deviations")
                if (deviations != null) {
                    println()
                    println("Deviation from Baseline:")
                    println("  HR Absolute Change:             ${deviations.get("hr_absolute_change")} bpm")
                    println("  HR Percent Change:              ${deviations.get("hr_pct_change")}%")
                    println("  Steps Absolute Change:          ${deviations.get("steps_absolute_change")}")
                    println("  Steps Percent Change:           ${deviations.get("steps_pct_change")}%")
                }

                val anomalyDetection = json.getAsJsonObject("anomaly_detection")
                if (anomalyDetection != null) {
                    println()
                    println("Anomaly Detection:")
                    println("  HR Elevated:                    ${anomalyDetection.get("hr_elevated")}")
                    println("  Steps Reduced:                  ${anomalyDetection.get("steps_reduced")}")
                    val flag = anomalyDetection.get("flag")?.asString ?: "UNKNOWN"
                    println()
                    println("  ALERT FLAG:                  $flag")
                }

                println()
                println("-" * 70)
            } catch (e: Exception) {
                println("Could not parse biomarker results: ${e.message}")
                println("Raw biomarker output:")
                println(biomarkerJson)
            }
        }

        private operator fun String.times(count: Int): String = repeat(count)
    }
}

