package carp.dsp.demo.demos

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

                // 2. Copy data files to workspace
                setupWorkspaceFiles(demoResultsDir)
                println("Workspace prepared at: $demoResultsDir")

                // 3. Import workflow descriptor and generate execution plan
                val definition = WorkflowDescriptorImporter().import(descriptor)
                val planner = DefaultExecutionPlanner()
                val plan = planner.plan(definition)
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
                val report = executor.execute(plan, runId)
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
                val workflowName = "dbdp_covid_heart_rate__steps_analysis"
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

        private fun loadWorkflowYaml(): String {
            val resource = DbdpCovidDemo::class.java.classLoader
                .getResource("workflows/dbdp-covid-hr-steps.yaml")
                ?: throw IllegalStateException(
                    "Workflow YAML not found: workflows/dbdp-covid-hr-steps.yaml"
                )
            return resource.readText()
        }

        @OptIn(ExperimentalPathApi::class)
        private fun getDemoResultsDirectory(): Path {
            // Get the project root by locating the carp.dsp.demo directory
            val classPath = DbdpCovidDemo::class.java.protectionDomain.codeSource.location.toURI().path
            val projectRoot = java.io.File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                ?: throw IllegalStateException("Cannot determine project root")

            return projectRoot.toPath().resolve("demo_results").resolve("dbdp_covid")
        }

        @OptIn(ExperimentalPathApi::class)
        private fun setupWorkspaceFiles(workspaceDir: Path) {
            // Copy workflow YAML
            val workflowsDir = workspaceDir.resolve("resources/workflows")
            workflowsDir.createDirectories()
            val workflowResource = DbdpCovidDemo::class.java.classLoader
                .getResource("workflows/dbdp-covid-hr-steps.yaml")
                ?: throw IllegalStateException("Workflow YAML not found")
            Files.copy(
                workflowResource.openStream(),
                workflowsDir.resolve("dbdp-covid-hr-steps.yaml"),
                StandardCopyOption.REPLACE_EXISTING
            )

            // Copy data files
            val dataDir = workspaceDir.resolve("resources/data")
            dataDir.createDirectories()
            copyResourceFile("data/dbdp_covid_sample.csv", dataDir.resolve("dbdp_covid_sample.csv"))

            // Copy scripts
            val scriptsDir = workspaceDir.resolve("resources/scripts")
            scriptsDir.createDirectories()
            copyResourceFile("scripts/load_hr_steps.py", scriptsDir.resolve("load_hr_steps.py"))
            copyResourceFile("scripts/covid_hr_steps.py", scriptsDir.resolve("covid_hr_steps.py"))
            copyResourceFile("scripts/report_biomarker.py", scriptsDir.resolve("report_biomarker.py"))
        }

        private fun copyResourceFile(resourcePath: String, targetPath: Path) {
            val resource = DbdpCovidDemo::class.java.classLoader.getResource(resourcePath)
                ?: throw IllegalStateException("Resource not found: $resourcePath")
            Files.copy(
                resource.openStream(),
                targetPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }

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

